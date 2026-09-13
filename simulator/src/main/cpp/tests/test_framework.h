// Minimal test framework for the PocketFly native core. No external deps.

#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

namespace pftest {

using TestFn = void (*)();

struct TestCase {
    const char* name;
    TestFn fn;
};

inline std::vector<TestCase>& registry() {
    static std::vector<TestCase> r;
    return r;
}

inline int& failureCount() {
    static int f = 0;
    return f;
}

inline int registerTest(const char* name, TestFn fn) {
    registry().push_back({name, fn});
    return 0;
}

inline int runAllTests() {
    int passed = 0;
    for (const auto& t : registry()) {
        const int before = failureCount();
        t.fn();
        const bool ok = failureCount() == before;
        if (ok) ++passed;
        std::printf("%s %s\n", ok ? "PASS" : "FAIL", t.name);
    }
    std::printf("---\n%d/%zu tests passed, %d check(s) failed\n", passed, registry().size(),
                failureCount());
    return failureCount() == 0 ? 0 : 1;
}

}  // namespace pftest

#define PF_TEST(name)                                                        \
    static void pf_test_##name();                                            \
    static const int pf_reg_##name = pftest::registerTest(#name, pf_test_##name); \
    static void pf_test_##name()

#define PF_CHECK(cond)                                                     \
    do {                                                                   \
        if (!(cond)) {                                                     \
            std::printf("  check failed %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++pftest::failureCount();                                      \
        }                                                                  \
    } while (0)

#define PF_REQUIRE(cond)                                                   \
    do {                                                                   \
        if (!(cond)) {                                                     \
            std::printf("  require failed %s:%d: %s\n", __FILE__, __LINE__, #cond); \
            ++pftest::failureCount();                                      \
            return;                                                        \
        }                                                                  \
    } while (0)

#define PF_CHECK_NEAR(a, b, eps)                                           \
    do {                                                                   \
        const double _pa = static_cast<double>(a);                         \
        const double _pb = static_cast<double>(b);                         \
        if (!(_pb - eps <= _pa && _pa <= _pb + eps)) {                     \
            std::printf("  check_near failed %s:%d: %s=%f vs %s=%f\n", __FILE__, \
                        __LINE__, #a, _pa, #b, _pb);                       \
            ++pftest::failureCount();                                      \
        }                                                                  \
    } while (0)
