-- FSR-UPSC ModOptions (B42 native, Z-UET compatible pattern).
-- Displays under Main Menu -> Options -> Mods -> FSR-UPSC.
-- Values persist to Zomboid/Lua/ModOptions.ini (vanilla).
-- The javaagent reads ModOptions.ini at premain (restart applies the combo
-- order), and also accepts a live push via fsrupsc.FsrControl when loaded.
--
-- Modes   : fsr1 spatial, fsr2/fsr3 GL compute temporal (M0-M4, verified);
--           fsr1 rescue stays as the fallback when compute faults.
-- Presets : FidelityFX quality presets -- 1/1.285, 1/1.444, 1/1.667, 1/2.
-- FgTest  : software frame generation insert (default off; vsync ON).
local MOD_ID = "FSR_UPSC"
local MOD_NAME = "FSR-UPSC"

if PZAPI == nil or PZAPI.ModOptions == nil or PZAPI.ModOptions.create == nil then
    print("[FSR-UPSC] PZAPI.ModOptions not available, options hidden")
    return
end

local options = PZAPI.ModOptions:create(MOD_ID, MOD_NAME)

-- Shared handle (same pattern as Z-UET Z_UET_Options).
FSR_UPSC_Options = options

-- Mirrors fsrupsc.FsrSettings.UPSCALERS / QUALITIES / SCALES. Keep in sync.
local UPSCALERS = { "off", "bicubic", "fsr1", "fsr2", "fsr3" }
local QUALITIES = { "ultra_quality", "quality", "balanced", "performance", "native" }
local SCALES    = { "0", "50", "60", "69", "78", "83" }

-- 1-based default indexes (overwrite BEFORE apply runs on boot).
local DEF_UPSCALER  = 3 -- fsr1
local DEF_QUALITY   = 2 -- quality
local DEF_FGTEST    = 1 -- off

local function idxOf(t, v, fb)
    for i = 1, #t do
        if t[i] == v then return i end
    end
    return fb
end

local up = options:addComboBox("Upscaler", "UI_options_FSR_UPSC_Upscaler", "UI_options_FSR_UPSC_Upscaler_desc")
up:addItem("UI_options_FSR_UPSC_off", false)
up:addItem("UI_options_FSR_UPSC_bicubic", false)
up:addItem("UI_options_FSR_UPSC_fsr1", DEF_UPSCALER == 3)
up:addItem("UI_options_FSR_UPSC_fsr2", DEF_UPSCALER == 4)
up:addItem("UI_options_FSR_UPSC_fsr3", DEF_UPSCALER == 5)

local q = options:addComboBox("UpscalerQuality", "UI_options_FSR_UPSC_QualityTitle", "UI_options_FSR_UPSC_QualityTitle_desc")
q:addItem("UI_options_FSR_UPSC_ultra_quality", DEF_QUALITY == 1)
q:addItem("UI_options_FSR_UPSC_quality", DEF_QUALITY == 2)
q:addItem("UI_options_FSR_UPSC_balanced", DEF_QUALITY == 3)
q:addItem("UI_options_FSR_UPSC_performance", DEF_QUALITY == 4)
q:addItem("UI_options_FSR_UPSC_native", DEF_QUALITY == 5)

local s = options:addComboBox("UpscalerScalePct", "UI_options_FSR_UPSC_Scale", "UI_options_FSR_UPSC_Scale_desc")
s:addItem("UI_options_FSR_UPSC_scale0", true)
s:addItem("UI_options_FSR_UPSC_scale50", false)
s:addItem("UI_options_FSR_UPSC_scale60", false)
s:addItem("UI_options_FSR_UPSC_scale69", false)
s:addItem("UI_options_FSR_UPSC_scale78", false)
s:addItem("UI_options_FSR_UPSC_scale83", false)

options:addSlider("FsrSharpnessPct", "UI_options_FSR_UPSC_Sharp", 0, 100, 1, 80, "UI_options_FSR_UPSC_Sharp_desc")

local fg = options:addComboBox("FgTest", "UI_options_FSR_UPSC_FgTest", "UI_options_FSR_UPSC_FgTest_desc")
fg:addItem("UI_options_FSR_UPSC_FgOff", DEF_FGTEST == 1)
fg:addItem("UI_options_FSR_UPSC_FgMagenta", DEF_FGTEST == 2)
fg:addItem("UI_options_FSR_UPSC_FgDuplicate", DEF_FGTEST == 3)
fg:addItem("UI_options_FSR_UPSC_FgInterp", DEF_FGTEST == 4)

local DEF_FGPACE = 1 -- off
local fp = options:addComboBox("FgPace", "UI_options_FSR_UPSC_PaceTitle", "UI_options_FSR_UPSC_PaceTitle_desc")
fp:addItem("UI_options_FSR_UPSC_PaceOff", DEF_FGPACE == 1)
fp:addItem("UI_options_FSR_UPSC_PaceSafe", DEF_FGPACE == 2)
fp:addItem("UI_options_FSR_UPSC_PaceFast", DEF_FGPACE == 3)

options:addDescription("UI_options_FSR_UPSC_RestartRequired")
options:addDescription("UI_options_FSR_UPSC_Note")

local function findControl()
    -- Kahlua exposes loaded classes by package table; try the plain class
    -- name first, then the new fsrupsc namespace.
    local dc = rawget(_G, "FsrControl")
    if dc ~= nil then return dc end
    local ns = rawget(_G, "fsrupsc")
    if type(ns) == "table" then
        dc = rawget(ns, "FsrControl")
        if dc ~= nil then return dc end
    end
    return nil
end

options.apply = function(self)
    pcall(function()
        local ui = self:getOption("Upscaler")
        local qi = self:getOption("UpscalerQuality")
        local si = self:getOption("UpscalerScalePct")
        local sh = self:getOption("FsrSharpnessPct")
        local fi = self:getOption("FgTest")
        local pi = self:getOption("FgPace")
        local uv = UPSCALERS[ui and ui:getValue() or DEF_UPSCALER] or "fsr1"
        local qv = QUALITIES[qi and qi:getValue() or DEF_QUALITY] or "quality"
        local sv = SCALES[si and si:getValue() or 1] or "0"
        local shv = sh and sh:getValue() or 80
        local fgv = (fi and fi:getValue() or DEF_FGTEST) - 1 -- 0-based style
        -- FgPace combo index -> FgPresent style: 1=off(0) 2=safe(-1) 3=fast(-2)
        local fpv = ({ 0, -1, -2 })[(pi and pi:getValue() or DEF_FGPACE) or 1]
        local dc = findControl()
        if dc then
            if dc.setUpscaler then dc.setUpscaler(uv) end
            if dc.setUpscalerQuality then dc.setUpscalerQuality(qv) end
            if dc.setUpscalerScalePct then dc.setUpscalerScalePct(tonumber(sv) or 0) end
            if dc.setFsrSharpnessPct then dc.setFsrSharpnessPct(shv) end
            if dc.fsr2FgTest then dc.fsr2FgTest(fgv) end
            if dc.fsr2FgPace then dc.fsr2FgPace(fpv) end
            if dc.log then dc.log("option apply " .. uv .. "/" .. qv .. "/scale" .. sv .. "/sharp" .. tostring(shv) .. "/fg" .. tostring(fgv) .. "/pace" .. tostring(fpv)) end
            print("[FSR-UPSC] applied live: " .. uv .. "/" .. qv .. "/" .. sv .. "/sharp" .. tostring(shv) .. "/fg" .. tostring(fgv) .. "/pace" .. tostring(fpv))
        else
            print("[FSR-UPSC] saved (restart to apply): " .. uv .. "/" .. qv .. "/" .. sv .. "/sharp" .. tostring(shv) .. "/fg" .. tostring(fgv) .. "/pace" .. tostring(fpv))
        end
    end)
end

Events.OnGameBoot.Add(function()
    pcall(function()
        PZAPI.ModOptions:load()
        options:apply()
        local ui = options:getOption("Upscaler")
        print("[FSR-UPSC] OnGameBoot applied. UpscalerIdx=" .. tostring(ui and ui:getValue() or "?"))
    end)
end)
