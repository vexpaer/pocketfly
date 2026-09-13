// Tests for the brain package binary format reader.

#include "core/brain_format.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sys/stat.h>

#include "test_framework.h"

namespace {

void writeFile(const std::string& path, const std::string& bytes) {
    std::ofstream f(path, std::ios::binary);
    f.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
}

template <typename T>
std::string le(T v) {
    std::string out(sizeof(T), '\0');
    for (size_t i = 0; i < sizeof(T); ++i) {
        out[i] = static_cast<char>((v >> (8 * i)) & 0xff);
    }
    return out;
}

std::string f32(float v) {
    uint32_t bits;
    static_assert(sizeof(bits) == sizeof(v), "float32 expected");
    __builtin_memcpy(&bits, &v, 4);
    return le<uint32_t>(bits);
}

// 5 neurons, 2 groups, 6 edges:
//   0 -> 1,2 | 1 -> 3 | 2 -> 3 | 3 -> 4 | 4 -> (nothing)
// Groups: {0,1,2} in group 0, {3,4} in group 1.
struct TinyBrain {
    std::string dir;
    TinyBrain(const std::string& d) : dir(d) {
        mkdir(d.c_str(), 0755);
        // row offsets: 0,2,3,4,5,5
        std::string offsets = le<uint32_t>(0) + le<uint32_t>(2) + le<uint32_t>(3) +
                              le<uint32_t>(4) + le<uint32_t>(5) + le<uint32_t>(5);
        std::string targets = le<uint32_t>(1) + le<uint32_t>(2) + le<uint32_t>(3) +
                              le<uint32_t>(3) + le<uint32_t>(4);
        std::string weights;
        for (float w : {1.0f, 1.0f, 1.0f, 1.0f, 1.0f}) weights += f32(w);
        // group,type,region,side,flags per neuron
        std::string neurons;
        const uint16_t g[5] = {0, 0, 0, 1, 1};
        const uint16_t t[5] = {1, 1, 1, 2, 2};
        const uint16_t r[5] = {0, 0, 0, 1, 1};
        const uint8_t side[5] = {0, 1, 2, 2, 2};
        const uint8_t flags[5] = {1, 0, 0, 0, 2};
        for (int i = 0; i < 5; ++i) {
            neurons += le<uint16_t>(g[i]) + le<uint16_t>(t[i]) + le<uint16_t>(r[i]) +
                       std::string(1, static_cast<char>(side[i])) +
                       std::string(1, static_cast<char>(flags[i]));
        }
        writeFile(dir + "/row_offsets.bin", offsets);
        writeFile(dir + "/targets.bin", targets);
        writeFile(dir + "/weights.bin", weights);
        writeFile(dir + "/neurons.bin", neurons);
    }
};

std::string tempDir(const char* name) {
    std::string tmpl = std::string("/tmp/pftest_") + name + "_XXXXXX";
    std::vector<char> buf(tmpl.begin(), tmpl.end());
    buf.push_back('\0');
    const char* created = mkdtemp(buf.data());
    return created;
}

}  // namespace

PF_TEST(FormatLoadValidBrain) {
    const std::string dir = tempDir("valid");
    TinyBrain brain(dir);
    pocketfly::BrainData data;
    std::string error;
    PF_REQUIRE(pocketfly::loadBrainFromDirectory(dir, 5, 5, 2, 1.0f, &data, &error));
    PF_CHECK(data.meta.neuronCount == 5);
    PF_CHECK(data.meta.edgeCount == 5);
    PF_REQUIRE(data.rowOffsets.size() == 6);
    PF_CHECK(data.rowOffsets[0] == 0 && data.rowOffsets[5] == 5);
    PF_REQUIRE(data.targets.size() == 5);
    PF_CHECK(data.targets[0] == 1 && data.targets[1] == 2 && data.targets[4] == 4);
    PF_CHECK_NEAR(data.weights[0], 1.0f, 1e-6);
    PF_REQUIRE(data.groupIdx.size() == 5);
    PF_CHECK(data.groupIdx[0] == 0 && data.groupIdx[4] == 1);
    PF_CHECK(data.flags[0] == pocketfly::kNeuronFlagInput);
    PF_CHECK(data.flags[4] == pocketfly::kNeuronFlagOutput);
    PF_CHECK(data.side[0] == pocketfly::kSideLeft && data.side[2] == pocketfly::kSideMid);
}

PF_TEST(FormatRejectsCountMismatch) {
    const std::string dir = tempDir("mismatch");
    TinyBrain brain(dir);
    pocketfly::BrainData data;
    std::string error;
    PF_CHECK(!pocketfly::loadBrainFromDirectory(dir, 7, 5, 2, 1.0f, &data, &error));
    PF_CHECK(!pocketfly::loadBrainFromDirectory(dir, 5, 9, 2, 1.0f, &data, &error));
}

PF_TEST(FormatRejectsCorruptTargets) {
    const std::string dir = tempDir("badtarget");
    TinyBrain brain(dir);
    // Point an edge at neuron 99.
    std::string targets = le<uint32_t>(1) + le<uint32_t>(2) + le<uint32_t>(3) +
                          le<uint32_t>(99) + le<uint32_t>(4);
    writeFile(dir + "/targets.bin", targets);
    pocketfly::BrainData data;
    std::string error;
    PF_CHECK(!pocketfly::loadBrainFromDirectory(dir, 5, 5, 2, 1.0f, &data, &error));
}

PF_TEST(FormatRejectsTruncatedFiles) {
    const std::string dir = tempDir("truncated");
    TinyBrain brain(dir);
    writeFile(dir + "/weights.bin", std::string(3, '\0'));  // wrong size
    pocketfly::BrainData data;
    std::string error;
    PF_CHECK(!pocketfly::loadBrainFromDirectory(dir, 5, 5, 2, 1.0f, &data, &error));
}

PF_TEST(FormatRejectsMissingDirectory) {
    pocketfly::BrainData data;
    std::string error;
    PF_CHECK(!pocketfly::loadBrainFromDirectory("/tmp/definitely_not_here_42", 0, 0, 0, 1.0f,
                                                &data, &error));
}

PF_TEST(FormatAppliesWeightScale) {
    const std::string dir = tempDir("scale");
    TinyBrain brain(dir);
    pocketfly::BrainData data;
    std::string error;
    PF_REQUIRE(pocketfly::loadBrainFromDirectory(dir, 5, 5, 2, 4.0f, &data, &error));
    PF_CHECK_NEAR(data.weights[0], 0.25f, 1e-6);
}

PF_TEST(FormatReadsU16Weights) {
    const std::string dir = tempDir("u16");
    mkdir(dir.c_str(), 0755);
    std::string offsets = le<uint32_t>(0) + le<uint32_t>(1) + le<uint32_t>(2);
    std::string targets = le<uint32_t>(1) + le<uint32_t>(1);
    std::string weights = le<uint16_t>(10) + le<uint16_t>(20);
    std::string neurons;
    for (int i = 0; i < 2; ++i) {
        neurons += le<uint16_t>(0) + le<uint16_t>(0) + le<uint16_t>(0) +
                   std::string(1, '\2') + std::string(1, '\0');
    }
    writeFile(dir + "/row_offsets.bin", offsets);
    writeFile(dir + "/targets.bin", targets);
    writeFile(dir + "/weights.bin", weights);
    writeFile(dir + "/neurons.bin", neurons);
    pocketfly::BrainData data;
    data.meta.weightType = "u16";
    std::string error;
    PF_REQUIRE(pocketfly::loadBrainFromDirectory(dir, 2, 2, 1, 10.0f, &data, &error));
    PF_CHECK_NEAR(data.weights[0], 1.0f, 1e-6);
    PF_CHECK_NEAR(data.weights[1], 2.0f, 1e-6);
}
