#pragma once
#include <cstdint>
struct CpuContext;

namespace GameMapSweep {
void Tick(CpuContext* cpu, uint32_t presentedFrames);
}
