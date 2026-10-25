-- FSR-UPSC entry point.
-- Options UI lives in FSR_UPSC_Options.lua (B42 native PZAPI.ModOptions).
-- This file intentionally does nothing else: the old PZOptions.addTab path
-- does not exist in stock and prevented the Mod Options entry from showing.
print("[FSR-UPSC] mod_main loaded (see Options -> Mods -> FSR-UPSC)")

-- The stock view-cone opacity is set in options.ini instead (viewConeOpacity=0).
-- Lua cannot reach PerformanceSettings: require() only loads Lua modules, and
-- getSpecificPlayer(-1) throws ArrayIndexOutOfBoundsException on the player array,
-- which dumped a Lua stack trace every game start. PZ reads options.ini at
-- start-up and only rewrites it when the option is changed, so the value there
-- survives every launch.
