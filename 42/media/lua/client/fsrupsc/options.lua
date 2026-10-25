-- FSR-UPSC: Optimizations tab stub.
-- Real wiring: PerformanceSettings forwards to fsrupsc.FsrConfig/UserOptions.
local TAB = "FSR-UPSC"

local SECTIONS = {
  {
    title = "Upscaling (world pass only, UI stays full-res)",
    entries = {
      { key = "upscaler", label = "Upscaler",
        choices = { "off", "bicubic", "fsr1", "fsr2", "fsr3" },
        tip = "fsr1 = spatial EASU+RCAS; fsr2/fsr3 = GL compute temporal upscaling (fsr1 rescue on compute fault). Key: upscaler." },
      { key = "upscalerQuality", label = "Upscaler quality (render size)",
        choices = { "ultra_quality", "quality", "balanced", "performance", "native" },
        tip = "FidelityFX presets: ultra_quality 1/1.285, quality 1/1.444, balanced 1/1.667, performance 1/2, native = full resolution. Key: upscalerQuality." },
      { key = "upscalerScalePct", label = "Explicit render scale (%)",
        choices = { "0", "50", "60", "69", "78", "83" },
        tip = "0 = use the quality preset. Sizes are snapped to a multiple of 16 (83 = exact 1600x900 at 1080p). Key: upscalerScalePct." },
      { key = "fsrSharpnessPct", label = "RCAS sharpening (%)",
        choices = { "0", "40", "80", "100" },
        tip = "Applied after EASU. Key: fsrSharpnessPct." },
      { key = "upscalerObjectMv", label = "Motion vectors for characters/vehicles",
        choices = { "true", "false" },
        tip = "Phase 2 temporal input. Key: upscalerObjectMv." },
    },
  },
}

return { TAB = TAB, SECTIONS = SECTIONS }
