// PocketFly neural runtime.
//
// Simplified leaky integrate-and-fire dynamics over a CSR connectome:
//
//   V[i,t+1] = decay * V[i,t] + sum_j (spike[j,t] * w[j,i]) * gain + drive[i] + noise
//   spike[i,t+1] = V[i,t+1] >= threshold, then V resets to 0
//
// Neurons that spiked on the previous step are refractory for one step.
// "Activity" is an exponential moving average of spiking, used for readouts.
//
// These are computational parameters for a simplified model, NOT biologically
// fitted Drosophila electrophysiology. See docs/scientific-limitations.md.
//
// Threading: all calls must come from a single thread (the Kotlin layer
// confines the runtime to one simulation thread).

#pragma once

#include "brain_format.h"

#include <memory>
#include <random>
#include <vector>

namespace pocketfly {

struct RuntimeParams {
    float decay = 0.82f;            // membrane potential retention per step
    float threshold = 1.0f;         // spike threshold
    float gain = 1.0f;              // global synaptic gain
    float noise = 0.0f;             // stddev of per-neuron gaussian noise per step
    float activationDecay = 0.85f;  // EMA factor for the activity readout
    bool refractory = true;         // 1-step refractory after a spike
    float resetFraction = 0.0f;     // fraction of V kept after a spike
};

struct SimulationStats {
    uint64_t neuronCount = 0;
    uint64_t edgeCount = 0;
    uint32_t activeNeurons = 0;   // neurons that spiked on the last step
    uint32_t ablatedNeurons = 0;
    double stepsPerSecond = 0.0;
    double lastStepMs = 0.0;
    double meanStepMs = 0.0;
    uint64_t totalSteps = 0;
    double approxMemoryBytes = 0.0;
};

struct NeuronInfo {
    uint32_t id = 0;
    uint16_t group = 0;
    uint16_t type = 0;
    uint16_t region = 0;
    uint8_t side = 0;
    uint8_t flags = 0;
    uint32_t inDegree = 0;
    uint32_t outDegree = 0;
    bool enabled = true;
    float activity = 0.f;
    float potential = 0.f;
};

class Runtime {
public:
    Runtime() = default;
    ~Runtime() = default;
    Runtime(const Runtime&) = delete;
    Runtime& operator=(const Runtime&) = delete;

    bool loadFromDirectory(const std::string& dir,
                           uint32_t expectedNeurons,
                           uint64_t expectedEdges,
                           uint32_t expectedGroups,
                           float weightScale,
                           std::string* error);

    // Human-readable reason for the most recent load failure.
    const std::string& lastError() const { return lastError_; }

    bool isLoaded() const { return brain_ != nullptr; }
    const BrainData& brain() const { return *brain_; }

    // Test hook: install an in-memory brain without touching the filesystem.
    void adoptBrain(std::shared_ptr<BrainData> data) {
        brain_ = std::move(data);
        allocateState();
        reset();
    }

    void reset();
    void step(uint32_t n = 1);

    // Sensory drive is persistent: it is added to the membrane every step
    // until changed or cleared. Values map onto the group's member neurons
    // in index order; group neurons beyond `count` are zeroed.
    void setInput(uint32_t neuronId, float value);
    void setInputGroup(uint16_t groupId, const float* values, uint32_t count);
    void clearInputs();

    float getActivity(uint32_t neuronId) const;
    float getPotential(uint32_t neuronId) const;
    float getGroupMeanActivity(uint16_t groupId) const;
    uint32_t getGroupSize(uint16_t groupId) const;
    uint32_t getActiveNeuronCount() const { return lastActive_; }
    SimulationStats getStats() const;

    // Ablation: disabled neurons never spike, receive nothing, and are
    // excluded from group activity means. Fully reversible.
    bool setNeuronEnabled(uint32_t neuronId, bool enabled);
    void setGroupEnabled(uint16_t groupId, bool enabled);
    void clearAblations();
    uint32_t ablatedCount() const { return ablatedCount_; }

    void setParams(const RuntimeParams& p) { params_ = p; }
    const RuntimeParams& params() const { return params_; }

    NeuronInfo getNeuronInfo(uint32_t neuronId) const;

private:
    void allocateState();

    std::shared_ptr<BrainData> brain_;

    std::vector<float> potential_;
    std::vector<float> activation_;
    std::vector<float> drive_;
    std::vector<float> incoming_;
    std::vector<uint8_t> spiked_;
    std::vector<uint8_t> enabled_;
    std::vector<uint32_t> inDegree_;
    std::vector<uint32_t> outDegree_;

    RuntimeParams params_;
    std::mt19937 rng_{20260912u};
    std::normal_distribution<float> noise_{0.f, 1.f};

    uint32_t lastActive_ = 0;
    uint32_t ablatedCount_ = 0;
    uint64_t totalSteps_ = 0;
    double lastStepMs_ = 0.0;
    double meanStepMs_ = 0.0;
    double approxMem_ = 0.0;
    std::string lastError_;
};

}  // namespace pocketfly
