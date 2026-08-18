// ---------------------------------------------------------------------------
// units.h — metric <-> imperial conversion, display-time only.
//
// All sensor values are stored and computed in metric (km, km/h, L, km/L, °C).
// These helpers convert for DISPLAY only. They must never be used for storage,
// NVS, or sensor math — only at render time, and only when UNITS_IMPERIAL is
// true. Conversion factors follow plan §15.1: Imperial = US units
// (US gallon 0.264172, US MPG 2.352145833, MPH), so everything pairs with MPH.
// ---------------------------------------------------------------------------
#ifndef UNITS_H
#define UNITS_H

#include <cmath>

inline float kmhToMph(float kmh) { return kmh * 0.621371f; }
inline float kmToMi(float km) { return km * 0.621371f; }
inline float litersToGal(float l) { return l * 0.264172f; }
inline float kmlToMpg(float kml) { return kml * 2.352145833f; }
inline float cToF(float c) { return c * 1.8f + 32.0f; }

// Unit label strings. Match the existing dashboard label text and casing:
//   speed/economy/fuel/odo labels are uppercase (MPH / MPG / MI / GAL),
//   the engine temp label is lowercase to match the existing "c".
inline const char *speedUnitLabel(bool imp) { return imp ? "MPH" : "KM/H"; }
inline const char *odoUnitLabel(bool imp) { return imp ? " MI" : " KM"; }
inline const char *economyUnitLabel(bool imp) { return imp ? "MPG" : "KM/L"; }
inline const char *fuelUnitLabel(bool imp) { return imp ? "GAL" : "L"; }
inline const char *tempUnitLabel(bool imp) { return imp ? "f" : "c"; }

#endif // UNITS_H
