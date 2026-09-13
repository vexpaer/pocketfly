// PocketFly brain package format reader.
//
// A loaded brain is a directory containing:
//   row_offsets.bin  - CSR row offsets, (N+1) entries, uint32 or uint64
//   targets.bin      - CSR target indices, E entries, uint32
//   weights.bin      - edge weights, E entries, float32 or uint16 (synapse counts)
//   neurons.bin      - per-neuron metadata SoA: uint16 group, uint16 type,
//                      uint16 region, uint8 side, uint8 flags, for each of N neurons
//   manifest.json    - metadata (validated by the Kotlin layer, not parsed here)
//
// All integers are little-endian. Neuron ids are implicit: 0..N-1.
// flags bit0 = sensory/input neuron, bit1 = output/descending neuron.
// side: 0 = left, 1 = right, 2 = mid.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace pocketfly {

constexpr uint32_t kSupportedFormatVersion = 1;
constexpr uint8_t kNeuronFlagInput = 0x01;
constexpr uint8_t kNeuronFlagOutput = 0x02;
constexpr uint8_t kSideLeft = 0;
constexpr uint8_t kSideRight = 1;
constexpr uint8_t kSideMid = 2;

struct BrainMeta {
    uint32_t neuronCount = 0;
    uint64_t edgeCount = 0;
    std::string offsetType = "u32";  // u32 | u64
    std::string weightType = "f32";  // f32 | u16
};

struct BrainData {
    BrainMeta meta;

    // Graph (CSR), size E.
    std::vector<uint64_t> rowOffsets;  // N+1
    std::vector<uint32_t> targets;     // E
    std::vector<float> weights;        // E, normalized at load by 1/weightScale

    // Neuron metadata, size N (structure of arrays).
    std::vector<uint16_t> groupIdx;
    std::vector<uint16_t> typeIdx;
    std::vector<uint16_t> regionIdx;
    std::vector<uint8_t> side;
    std::vector<uint8_t> flags;
};

// Loads the binary files in `dir` and validates them against the expected
// counts (0 = infer from files, no cross-check). Every target index is bounds
// checked, so a corrupt package can never produce an out-of-bounds access.
// On failure returns false and sets `error`.
bool loadBrainFromDirectory(const std::string& dir,
                            uint32_t expectedNeurons,
                            uint64_t expectedEdges,
                            uint32_t expectedGroups,
                            float weightScale,
                            BrainData* out,
                            std::string* error);

}  // namespace pocketfly
