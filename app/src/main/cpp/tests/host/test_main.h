#pragma once

#include <cmath>
#include <cstdio>
#include <vector>

struct TestCase { const char* name; void (*fn)(); };
inline std::vector<TestCase>& tests() { static std::vector<TestCase> t; return t; }
struct Registrar { Registrar(const char* n, void (*f)()) { tests().push_back({n, f}); } };
inline int failures = 0;
#define CHECK(c) do { if (!(c)) { std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #c); ++failures; } } while (0)
#define CHECK_NEAR(a,b,e) CHECK(std::fabs((a)-(b)) <= (e))
#define REGISTER_TEST(f) static Registrar registrar_##f(#f, f)
