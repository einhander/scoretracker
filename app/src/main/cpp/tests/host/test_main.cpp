#include "tests/host/test_main.h"
int main() { for (const auto& t : tests()) t.fn(); return failures ? 1 : 0; }
