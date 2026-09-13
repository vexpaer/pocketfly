#include "runtime.h"

#include <algorithm>
#include <chrono>

namespace pocketfly {

bool Runtime::loadFromDirectory(const std::string& dir,
                                uint32_t expectedNeurons,
                                uint64_t expectedEdges,
                                uint32_t expectedGroups,
                                float weightScale,
                                std::string* error) {
    auto data = std::make_shared<BrainData>();
    data->meta.offsetType = "u32";
    data->meta.weightType = "f32";
    if (!loadBrainFromDirectory(dir, expectedNeurons, expectedEdges, expectedGroups,
                                weightScale, data.get(), &lastError_)) {
        if (error != nullptr) *error = lastError_;
        return false;
    }
    lastError_.clear();
    brain_ = std::move(data);
    allocateState();
    reset();
    return true;
}

void Runtime::allocateState() {
    const uint32_t n = brain_->meta.neuronCount;
    potential_.assign(n, 0.f);
    activation_.assign(n, 0.f);
    drive_.assign(n, 0.f);
    incoming_.assign(n, 0.f);
    spiked_.assign(n, 0);
    enabled_.assign(n, 1);

    inDegree_.assign(n, 0);
    outDegree_.assign(n, 0);
    // outDegree from row lengths, inDegree from target histogram.
    for (uint32_t i = 0; i < n; ++i) {
        outDegree_[i] = static_cast<uint32_t>(brain_->rowOffsets[i + 1] - brain_->rowOffsets[i]);
    }
    std::fill(inDegree_.begin(), inDegree_.end(), 0u);
    for (uint64_t e = 0; e < brain_->meta.edgeCount; ++e) {
        ++inDegree_[brain_->targets[e]];
    }

    double mem = double(sizeof(float)) * (4.0 * n) + double(sizeof(uint8_t)) * (2.0 * n) +
                 double(sizeof(uint32_t)) * (2.0 * n) +
                 double(sizeof(uint32_t)) * brain_->targets.size() +
                 double(sizeof(float)) * brain_->weights.size() +
                 double(sizeof(uint64_t)) * brain_->rowOffsets.size() +
                 double(sizeof(uint16_t)) * 3.0 * n + double(sizeof(uint8_t)) * 2.0 * n;
    approxMem_ = mem;
    ablatedCount_ = 0;
}
void Runtime::reset() {
    if (!brain_) return;
    std::fill(potential_.begin(), potential_.end(), 0.f);
    std::fill(activation_.begin(), activation_.end(), 0.f);
    std::fill(incoming_.begin(), incoming_.end(), 0.f);
    std::fill(spiked_.begin(), spiked_.end(), 0);
    lastActive_ = 0;
    totalSteps_ = 0;
    // Note: drive_ and enabled_ deliberately survive reset(); use
    // clearInputs()/clearAblations() for those.
}

void Runtime::step(uint32_t n) {
    if (!brain_) return;
    const auto& b = *brain_;
    const uint32_t N = b.meta.neuronCount;
    const float decay = params_.decay;
    const float threshold = params_.threshold;
    const float gain = params_.gain;
    const float noise = params_.noise;
    const float actDecay = params_.activationDecay;
    const float resetFraction = params_.resetFraction;

    for (uint32_t s = 0; s < n; ++s) {
        const auto t0 = std::chrono::steady_clock::now();

        // 1. Scatter previous spikes along the CSR edges.
        std::fill(incoming_.begin(), incoming_.end(), 0.f);
        for (uint32_t i = 0; i < N; ++i) {
            if (spiked_[i] == 0 || enabled_[i] == 0) continue;
            const uint64_t begin = b.rowOffsets[i];
            const uint64_t end = b.rowOffsets[i + 1];
            for (uint64_t e = begin; e < end; ++e) {
                const uint32_t t = b.targets[e];
                if (enabled_[t]) {
                    incoming_[t] += gain * b.weights[e];
                }
            }
        }

        // 2. Integrate.
        uint32_t active = 0;
        for (uint32_t i = 0; i < N; ++i) {
            if (enabled_[i] == 0) {
                activation_[i] *= 0.5f;
                spiked_[i] = 0;
                continue;
            }
            if (params_.refractory && spiked_[i] != 0) {
                // Refractory step: membrane clamped, no new spike.
                potential_[i] = 0.f;
                spiked_[i] = 0;
                activation_[i] = activation_[i] * actDecay;
                continue;
            }
            float v = decay * potential_[i] + incoming_[i] + drive_[i];
            if (noise > 0.f) {
                v += noise_(rng_) * noise;
            }
            uint8_t spike = 0;
            if (v >= threshold) {
                spike = 1;
                v = resetFraction * v;
                ++active;
            }
            potential_[i] = v;
            spiked_[i] = spike;
            activation_[i] = activation_[i] * actDecay + static_cast<float>(spike);
        }
        lastActive_ = active;
        ++totalSteps_;

        const auto t1 = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        lastStepMs_ = ms;
        meanStepMs_ = meanStepMs_ == 0.0 ? ms : 0.9 * meanStepMs_ + 0.1 * ms;
    }
}

void Runtime::setInput(uint32_t neuronId, float value) {
    if (!brain_ || neuronId >= brain_->meta.neuronCount) return;
    drive_[neuronId] = value;
}

void Runtime::setInputGroup(uint16_t groupId, const float* values, uint32_t count) {
    if (!brain_) return;
    const uint32_t N = brain_->meta.neuronCount;
    for (uint32_t i = 0; i < N; ++i) {
        if (brain_->groupIdx[i] == groupId) {
            drive_[i] = 0.f;
        }
    }
    uint32_t k = 0;
    for (uint32_t i = 0; i < N && k < count; ++i) {
        if (brain_->groupIdx[i] == groupId) {
            drive_[i] = values[k++];
        }
    }
}

void Runtime::clearInputs() {
    std::fill(drive_.begin(), drive_.end(), 0.f);
}

float Runtime::getActivity(uint32_t neuronId) const {
    if (!brain_ || neuronId >= brain_->meta.neuronCount) return 0.f;
    return activation_[neuronId];
}

float Runtime::getPotential(uint32_t neuronId) const {
    if (!brain_ || neuronId >= brain_->meta.neuronCount) return 0.f;
    return potential_[neuronId];
}

float Runtime::getGroupMeanActivity(uint16_t groupId) const {
    if (!brain_) return 0.f;
    float sum = 0.f;
    uint32_t count = 0;
    const uint32_t N = brain_->meta.neuronCount;
    for (uint32_t i = 0; i < N; ++i) {
        if (brain_->groupIdx[i] == groupId && enabled_[i]) {
            sum += activation_[i];
            ++count;
        }
    }
    return count == 0 ? 0.f : sum / static_cast<float>(count);
}

uint32_t Runtime::getGroupSize(uint16_t groupId) const {
    if (!brain_) return 0;
    uint32_t count = 0;
    for (size_t i = 0; i < brain_->groupIdx.size(); ++i) {
        if (brain_->groupIdx[i] == groupId) ++count;
    }
    return count;
}

SimulationStats Runtime::getStats() const {
    SimulationStats s;
    if (brain_) {
        s.neuronCount = brain_->meta.neuronCount;
        s.edgeCount = brain_->meta.edgeCount;
    }
    s.activeNeurons = lastActive_;
    s.ablatedNeurons = ablatedCount_;
    s.stepsPerSecond = meanStepMs_ > 0.0 ? 1000.0 / meanStepMs_ : 0.0;
    s.lastStepMs = lastStepMs_;
    s.meanStepMs = meanStepMs_;
    s.totalSteps = totalSteps_;
    s.approxMemoryBytes = approxMem_;
    return s;
}

bool Runtime::setNeuronEnabled(uint32_t neuronId, bool enabled) {
    if (!brain_ || neuronId >= brain_->meta.neuronCount) return false;
    const uint8_t want = enabled ? 1 : 0;
    if (enabled_[neuronId] != want) {
        enabled_[neuronId] = want;
        ablatedCount_ = static_cast<uint32_t>(std::count(enabled_.begin(), enabled_.end(), 0));
    }
    return true;
}

void Runtime::setGroupEnabled(uint16_t groupId, bool enabled) {
    if (!brain_) return;
    for (size_t i = 0; i < brain_->groupIdx.size(); ++i) {
        if (brain_->groupIdx[i] == groupId) {
            enabled_[i] = enabled ? 1 : 0;
        }
    }
    ablatedCount_ = static_cast<uint32_t>(std::count(enabled_.begin(), enabled_.end(), 0));
}

void Runtime::clearAblations() {
    std::fill(enabled_.begin(), enabled_.end(), 1);
    ablatedCount_ = 0;
}

NeuronInfo Runtime::getNeuronInfo(uint32_t neuronId) const {
    NeuronInfo info;
    if (!brain_ || neuronId >= brain_->meta.neuronCount) return info;
    info.id = neuronId;
    info.group = brain_->groupIdx[neuronId];
    info.type = brain_->typeIdx[neuronId];
    info.region = brain_->regionIdx[neuronId];
    info.side = brain_->side[neuronId];
    info.flags = brain_->flags[neuronId];
    info.inDegree = inDegree_[neuronId];
    info.outDegree = outDegree_[neuronId];
    info.enabled = enabled_[neuronId] != 0;
    info.activity = activation_[neuronId];
    info.potential = potential_[neuronId];
    return info;
}

}  // namespace pocketfly
