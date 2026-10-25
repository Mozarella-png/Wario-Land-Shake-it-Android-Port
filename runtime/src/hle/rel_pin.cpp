#include "hle_stubs.h"

#include "abi_bridge.h"
#include "memory.h"
#include "runtime_log.h"

namespace {

constexpr uint32_t kMemoryWp = 0x80001870u /* WARIO-QUARANTINE: base 0x8056D188 sin verificar */;
constexpr uint32_t kMemAllocFromExpHeapEx = 0x8040CD74u; // MEMAllocFromExpHeapEx(heap, size, align)
constexpr uint32_t kMemFreeToExpHeap = 0x8040CE24u;      // MEMFreeToExpHeap(heap, block)
constexpr uint32_t kRelImage = 0x80001878u /* WARIO-QUARANTINE: base 0x80BF6840 sin verificar */;
constexpr uint32_t kRelMinSize = 0x300000u;             // only relF is this big (decompressed ~3.2 MB)

uint32_t ExpAlloc(const CpuContext* cpu, uint32_t heap, uint32_t size, int32_t align) {
    CpuContext call = *cpu;
    call.gpr[3] = heap;
    call.gpr[4] = size;
    call.gpr[5] = static_cast<uint32_t>(align);
    InvokeIndirectCpu(kMemAllocFromExpHeapEx, &call);
    return call.gpr[3];
}

void ExpFree(const CpuContext* cpu, uint32_t heap, uint32_t block) {
    CpuContext call = *cpu;
    call.gpr[3] = heap;
    call.gpr[4] = block;
    InvokeIndirectCpu(kMemFreeToExpHeap, &call);
}

} // namespace

// 0x801A5634 -> __memAlloc(heapId, size): MEMAllocFromExpHeapEx(wp->heapHandle[heapId], size, 0x20) plus the pin.
extern "C" void GAME__memAlloc_Cpu(CpuContext* ctx)
{
    const uint32_t heapId = ctx->gpr[3];
    const uint32_t size = ctx->gpr[4];
    const uint32_t heap = Memory::Read32(Memory::Read32(kMemoryWp) + heapId * 4u);
    uint32_t block = ExpAlloc(ctx, heap, size, 0x20);
    if (heapId == 0 && size >= kRelMinSize && block != 0 && block < kRelImage) {
        ExpFree(ctx, heap, block);
        uint32_t pad = kRelImage - block - 0x10u;  // minus the block header the padding's successor needs
        for (int attempt = 0; attempt < 4; ++attempt) {
            const uint32_t padding = ExpAlloc(ctx, heap, pad, 0x20);
            block = ExpAlloc(ctx, heap, size, 0x20);
            if (block == kRelImage || block == 0) {
                break;
            }
            ExpFree(ctx, heap, block);
            ExpFree(ctx, heap, padding);
            pad += kRelImage - block;
        }
        RT_LOGF(RT_TAG_HLE, "[relpin] relF block padded to 0x%08X (%s)\n", block, block == kRelImage ? "pinned" : "FAILED");
    } else if (heapId == 0 && size >= kRelMinSize && block != kRelImage) {
        RT_LOGF(RT_TAG_HLE, "[relpin] relF block at 0x%08X is above the pre-linked image 0x%08X\n", block, kRelImage);
    }
    if (block == 0) {
        RT_LOGF(RT_TAG_HLE, "[relpin] __memAlloc(%u, %u) failed\n", heapId, size);  // the game's own assert follows
    }
    ctx->gpr[3] = block;
}

#if 0 /* WARIO-TODO: falta la direccion de __memAlloc (base 801A5634) */
PPC_NATIVE_OVERRIDE_VOID(801A5634, GAME__memAlloc_Cpu, (CpuContext* ctx), (ctx));
#endif
