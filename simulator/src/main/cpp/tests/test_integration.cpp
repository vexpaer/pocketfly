// Integration tests: full stimulus -> propagation -> output loop against the
// committed sample connectome, plus a benchmark. The sample brain directory is
// resolved from (in order): --sample <dir> argument, POCKETFLY_SAMPLE_DIR env
// var. If unavailable the tests are skipped (CI always provides it).

#include "core/runtime.h"

#include <chrono>
#include <cstdlib>
#include <string>

#include "test_framework.h"

namespace {

std::string sampleDir() {
    if (const char* env = std::getenv("POCKETFLY_SAMPLE_DIR")) {
        std::string s(env);
        if (!s.empty()) return s;
    }
    return {};
}

// Drive every neuron of a group with `value`. setInputGroup maps values onto
// group members in index order, so build the array in member order.
void driveGroup(pocketfly::Runtime& rt, const pocketfly::BrainData& b, uint16_t group,
                float value) {
    std::vector<float> values;
    for (uint32_t i = 0; i < b.meta.neuronCount; ++i) {
        if (b.groupIdx[i] == group) values.push_back(value);
    }
    rt.setInputGroup(group, values.data(), static_cast<uint32_t>(values.size()));
}

constexpr uint16_t kGroupVisLeft = 0;
constexpr uint16_t kGroupVisRight = 1;
constexpr uint16_t kGroupCxLeft = 4;
constexpr uint16_t kGroupDnLeft = 7;
constexpr uint16_t kGroupDnRight = 8;

}  // namespace

PF_TEST(IntegrationSampleBrainLoads) {
    const std::string dir = sampleDir();
    if (dir.empty()) {
        std::printf("  skipped: no sample brain available\n");
        return;
    }
    pocketfly::Runtime rt;
    std::string error;
    PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
    PF_CHECK(rt.brain().meta.neuronCount == 1024);
    PF_CHECK(rt.brain().meta.edgeCount > 10000);
    PF_CHECK(rt.getGroupSize(kGroupVisLeft) > 0);
    PF_CHECK(rt.getGroupSize(kGroupDnLeft) > 0);
}

PF_TEST(IntegrationSilentBaseline) {
    const std::string dir = sampleDir();
    if (dir.empty()) return void(std::printf("  skipped: no sample brain\n"));
    pocketfly::Runtime rt;
    std::string error;
    PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
    rt.setParams({0.82f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});  // no noise
    rt.step(300);
    PF_CHECK(rt.getActiveNeuronCount() == 0);
    PF_CHECK_NEAR(rt.getGroupMeanActivity(kGroupDnLeft), 0.0f, 1e-6);
    PF_CHECK_NEAR(rt.getGroupMeanActivity(kGroupDnRight), 0.0f, 1e-6);
}

PF_TEST(IntegrationLeftLightTurnsLeft) {
    const std::string dir = sampleDir();
    if (dir.empty()) return void(std::printf("  skipped: no sample brain\n"));
    pocketfly::Runtime rt;
    std::string error;
    PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
    rt.setParams({0.82f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});

    driveGroup(rt, rt.brain(), kGroupVisLeft, 1.0f);
    rt.step(120);

    const float left = rt.getGroupMeanActivity(kGroupDnLeft);
    const float right = rt.getGroupMeanActivity(kGroupDnRight);
    std::printf("  left stimulus: DN_L=%.4f DN_R=%.4f\n", left, right);
    PF_CHECK(left > 0.05f);
    PF_CHECK(left > 1.5f * right);
}

PF_TEST(IntegrationRightLightTurnsRight) {
    const std::string dir = sampleDir();
    if (dir.empty()) return void(std::printf("  skipped: no sample brain\n"));
    pocketfly::Runtime rt;
    std::string error;
    PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
    rt.setParams({0.82f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});

    driveGroup(rt, rt.brain(), kGroupVisRight, 1.0f);
    rt.step(120);

    const float left = rt.getGroupMeanActivity(kGroupDnLeft);
    const float right = rt.getGroupMeanActivity(kGroupDnRight);
    std::printf("  right stimulus: DN_L=%.4f DN_R=%.4f\n", left, right);
    PF_CHECK(right > 0.05f);
    PF_CHECK(right > 1.5f * left);
}

PF_TEST(IntegrationAblationReducesBias) {
    const std::string dir = sampleDir();
    if (dir.empty()) return void(std::printf("  skipped: no sample brain\n"));

    // Control response.
    float controlLeft = 0.f, controlRight = 0.f;
    {
        pocketfly::Runtime rt;
        std::string error;
        PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
        rt.setParams({0.82f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});
        driveGroup(rt, rt.brain(), kGroupVisLeft, 1.0f);
        rt.step(120);
        controlLeft = rt.getGroupMeanActivity(kGroupDnLeft);
        controlRight = rt.getGroupMeanActivity(kGroupDnRight);
    }
    // Ablate the ipsilateral central pool, rerun.
    float ablatedLeft = 0.f, ablatedRight = 0.f;
    {
        pocketfly::Runtime rt;
        std::string error;
        PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
        rt.setParams({0.82f, 1.0f, 1.0f, 0.0f, 0.85f, true, 0.0f});
        rt.setGroupEnabled(kGroupCxLeft, false);
        driveGroup(rt, rt.brain(), kGroupVisLeft, 1.0f);
        rt.step(120);
        ablatedLeft = rt.getGroupMeanActivity(kGroupDnLeft);
        ablatedRight = rt.getGroupMeanActivity(kGroupDnRight);
    }
    const float controlBias = controlLeft - controlRight;
    const float ablatedBias = ablatedLeft - ablatedRight;
    std::printf("  bias control=%.4f ablated=%.4f\n", controlBias, ablatedBias);
    PF_CHECK(controlBias > 0.f);
    PF_CHECK(ablatedBias < controlBias);
}

PF_TEST(IntegrationBenchmark1000Steps) {
    const std::string dir = sampleDir();
    if (dir.empty()) return void(std::printf("  skipped: no sample brain\n"));
    pocketfly::Runtime rt;
    std::string error;
    PF_REQUIRE(rt.loadFromDirectory(dir, 1024, 0, 10, 50.0f, &error));
    rt.setParams({0.82f, 1.0f, 1.0f, 0.02f, 0.85f, true, 0.0f});
    driveGroup(rt, rt.brain(), kGroupVisLeft, 1.0f);
    driveGroup(rt, rt.brain(), kGroupVisRight, 0.6f);

    rt.step(100);  // warmup
    const auto t0 = std::chrono::steady_clock::now();
    rt.step(1000);
    const auto t1 = std::chrono::steady_clock::now();
    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    std::printf("  benchmark: 1000 steps in %.1f ms -> %.0f steps/s (%.3f ms/step), "
                "active=%u, mem=%.1f KB\n",
                ms, 1e6 / ms, ms / 1000.0, rt.getActiveNeuronCount(),
                rt.getStats().approxMemoryBytes / 1024.0);
    PF_CHECK(rt.getStats().totalSteps == 1100);
}
