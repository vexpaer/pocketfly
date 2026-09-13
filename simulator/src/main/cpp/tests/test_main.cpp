#include <cstring>

#include "test_framework.h"

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--sample") == 0 && i + 1 < argc) {
            setenv("POCKETFLY_SAMPLE_DIR", argv[++i], 1);
        }
    }
    return pftest::runAllTests();
}
