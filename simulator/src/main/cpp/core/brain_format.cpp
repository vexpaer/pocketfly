#include "brain_format.h"

#include <cstdio>
#include <cstring>
#include <fstream>

namespace pocketfly {
namespace {

bool readFileBytes(const std::string& path, std::vector<uint8_t>* out, std::string* error) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) {
        *error = "cannot open " + path;
        return false;
    }
    const std::streamoff size = f.tellg();
    if (size < 0) {
        *error = "cannot stat " + path;
        return false;
    }
    out->resize(static_cast<size_t>(size));
    f.seekg(0);
    if (size > 0 && !f.read(reinterpret_cast<char*>(out->data()), size)) {
        *error = "cannot read " + path;
        return false;
    }
    return true;
}

template <typename T>
T readLE(const uint8_t* p) {
    T v = 0;
    for (size_t i = 0; i < sizeof(T); ++i) {
        v |= static_cast<T>(p[i]) << (8 * i);
    }
    return v;
}

}  // namespace

bool loadBrainFromDirectory(const std::string& dir,
                            uint32_t expectedNeurons,
                            uint64_t expectedEdges,
                            uint32_t expectedGroups,
                            float weightScale,
                            BrainData* out,
                            std::string* error) {
    std::vector<uint8_t> offsetsRaw, targetsRaw, weightsRaw, neuronsRaw;
    if (!readFileBytes(dir + "/row_offsets.bin", &offsetsRaw, error)) return false;
    if (!readFileBytes(dir + "/targets.bin", &targetsRaw, error)) return false;
    if (!readFileBytes(dir + "/weights.bin", &weightsRaw, error)) return false;
    if (!readFileBytes(dir + "/neurons.bin", &neuronsRaw, error)) return false;

    const bool offsets64 = out->meta.offsetType == "u64";
    const bool weights16 = out->meta.weightType == "u16";
    if (out->meta.offsetType != "u32" && !offsets64) {
        *error = "unsupported offsetType: " + out->meta.offsetType;
        return false;
    }
    if (out->meta.weightType != "f32" && !weights16) {
        *error = "unsupported weightType: " + out->meta.weightType;
        return false;
    }
    if (weightScale <= 0.f) weightScale = 1.f;

    const size_t offsetEntry = offsets64 ? 8 : 4;
    if (offsetsRaw.size() < offsetEntry || offsetsRaw.size() % offsetEntry != 0) {
        *error = "row_offsets.bin size invalid";
        return false;
    }
    const size_t offsetCount = offsetsRaw.size() / offsetEntry;
    if (offsetCount < 2) {
        *error = "row_offsets.bin must hold at least 2 entries";
        return false;
    }
    const uint32_t n = static_cast<uint32_t>(offsetCount - 1);
    if (expectedNeurons != 0 && expectedNeurons != n) {
        *error = "neuron count mismatch: manifest says " + std::to_string(expectedNeurons) +
                 ", files hold " + std::to_string(n);
        return false;
    }

    if (targetsRaw.size() % 4 != 0) {
        *error = "targets.bin size invalid";
        return false;
    }
    const uint64_t e = targetsRaw.size() / 4;
    if (expectedEdges != 0 && expectedEdges != e) {
        *error = "edge count mismatch: manifest says " + std::to_string(expectedEdges) +
                 ", files hold " + std::to_string(e);
        return false;
    }
    const size_t weightEntry = weights16 ? 2 : 4;
    if (weightsRaw.size() != e * weightEntry) {
        *error = "weights.bin size does not match targets.bin";
        return false;
    }
    constexpr size_t kNeuronRecord = 2 + 2 + 2 + 1 + 1;
    if (neuronsRaw.size() != static_cast<size_t>(n) * kNeuronRecord) {
        *error = "neurons.bin size does not match neuron count";
        return false;
    }

    out->meta.neuronCount = n;
    out->meta.edgeCount = e;
    out->rowOffsets.resize(offsetCount);
    for (size_t i = 0; i < offsetCount; ++i) {
        out->rowOffsets[i] = offsets64
            ? readLE<uint64_t>(&offsetsRaw[i * 8])
            : readLE<uint32_t>(&offsetsRaw[i * 4]);
    }
    if (out->rowOffsets[0] != 0 || out->rowOffsets[offsetCount - 1] != e) {
        *error = "row_offsets.bin endpoints do not match edge count";
        return false;
    }
    for (size_t i = 1; i < offsetCount; ++i) {
        if (out->rowOffsets[i] < out->rowOffsets[i - 1]) {
            *error = "row_offsets.bin is not non-decreasing";
            return false;
        }
    }

    out->targets.resize(static_cast<size_t>(e));
    for (uint64_t i = 0; i < e; ++i) {
        const uint32_t t = readLE<uint32_t>(&targetsRaw[i * 4]);
        if (t >= n) {
            *error = "target index out of range at edge " + std::to_string(i);
            return false;
        }
        out->targets[i] = t;
    }

    out->weights.resize(static_cast<size_t>(e));
    const float invScale = 1.f / weightScale;
    for (uint64_t i = 0; i < e; ++i) {
        if (weights16) {
            out->weights[i] = static_cast<float>(readLE<uint16_t>(&weightsRaw[i * 2])) * invScale;
        } else {
            const uint32_t bits = readLE<uint32_t>(&weightsRaw[i * 4]);
            std::memcpy(&out->weights[i], &bits, 4);
            out->weights[i] *= invScale;
        }
    }

    out->groupIdx.resize(n);
    out->typeIdx.resize(n);
    out->regionIdx.resize(n);
    out->side.resize(n);
    out->flags.resize(n);
    for (uint32_t i = 0; i < n; ++i) {
        const uint8_t* rec = &neuronsRaw[static_cast<size_t>(i) * kNeuronRecord];
        out->groupIdx[i] = readLE<uint16_t>(rec);
        out->typeIdx[i] = readLE<uint16_t>(rec + 2);
        out->regionIdx[i] = readLE<uint16_t>(rec + 4);
        out->side[i] = rec[6];
        out->flags[i] = rec[7];
        if (expectedGroups != 0 && out->groupIdx[i] >= expectedGroups) {
            *error = "group index out of range at neuron " + std::to_string(i);
            return false;
        }
    }
    return true;
}

}  // namespace pocketfly
