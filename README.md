# NdMSpace — A Live-Coding Environment With Persistent, Bus-Stable NdM Nodes

*(for Live Coding in SuperCollider)*

NdMSpace provides a stable, persistent environment for managing **NdM** nodes as environment variables (`~key`), offering **bus-stable function replacement**, **per-instance fading**, **automatic reuse of argument buses**, and **safe namespace-level monitoring**.
It is designed for **live coding musicians** who constantly redefine running processes while keeping audio stable, without clicks, bus conflicts, or broken mappings.

The core engine is implemented in:

* `NdMSpace`        (environment controller and global NdM frontend)
* `NdM`             (per-node wrapper around Ndef, including fade/switch logic)
* `NdMError`        (lightweight error wrapper)
* `NdMSpaceSpec`    (front-end builder used by `nd { ... }`; not part of the engine itself)
* `NdMNameSpace`    (persistent bus/rate/tag history)
* `NdMTag`          (tag-based group control)

---

# Why NdMSpace? (Live-Coding Advantages)

Live coders working directly with Ndef often encounter the same issues.
NdMSpace addresses them by managing NdM nodes in a unified space.

## 1. **Persistent argument buses even when functions change**

When you change a function’s argument list, Ndef allocates **new buses**, breaking modulation chains.

NdMSpace + NdMNameSpace guarantee that:

* each key (e.g. `~osc`) retains a persistent mapping
* argument names (`freq`, `mod`, etc.) reuse the **same bus indices**
* modulation networks survive live edits
* no “zombie buses” accumulate

Whenever you write:

```supercollider
~osc = nd { |freq| SinOsc.ar(freq) };
```

or replace the function with another `nd { ... }`, the buses remain stable.

This is essential for live coders who frequently rewrite running nodes.

---

## 2. **Stable fade-in / fade-out for edits, play, stop, free**

With raw Ndef, `.play` and `.stop` may click depending on state.

NdM introduces two hidden control arguments:

* `ndmAmp`
* `ndmFade`

Your signal is multiplied with:

```supercollider
VarLag.kr(ndmAmp, ndmFade)
```

giving smooth transitions on:

```supercollider
~osc.fade = 2;
~osc.play;
```

The same applies to `.stop` and `.free`, and NdMSpace manages their timing cleanly.

---

## 3. **Server-synchronized play()**

NdM performs `Server:sync` after building the node, ensuring:

* the node exists
* controls are installed
* fade controls are ready

This eliminates the “first control message was ignored” issue.

---

## 4. **Explicit, flexible output-bus routing**

Via NdM:

* integers
* Bus objects
* arrays of bus indices

are normalized and rate-checked before patching.
NdMSpace keeps these mappings consistent across function updates.

---

## 5. **Argument-rate inference from key names**

If a key ends with `_a` or `_k`:

```supercollider
~osc = nd { |freq, pan| ... };  // NdM(\osc_a) → all arguments audio-rate
```

This provides predictable DSP graphs during live performance.

---

## 6. **A reliable monitor: NdMSpace + NdMNameSpace**

`NdMSpace` offers:

* node lookup (`nodes`, `get`)
* tag grouping (`playTag`, `stopTag`, `freeTag`)
* space-wide teardown (`stopAll`, `freeAll`, `reset`, `clean`)
* persistent bus mapping (`NdMNameSpace`)

Monitoring is safe and does not leak resources.

---

# Quick Start

```supercollider
a = NdMSpace.enter;

// Simple sine
~osc = nd { |freq|
    SinOsc.ar(freq, 0, 0.1)
}.out(0).play;

// Modulation routed via persistent bus
~lfo = nd {
    SinOsc.ar(1).range(200, 1200)
}.out(~osc[\freq]).play;

// Update the function live — buses remain stable
~osc = nd { |freq|
    LFTri.ar(freq, 0, 0.1)
};

// Cleaning up the space
a.reset;
a.exit;
```

---

# Basic Features

### Create or reuse:

```supercollider
~a = nd { |sth| SinOsc.ar(440, 0, 0.1) }.out(0);
```

### Play with fade:

```supercollider
~a.fade = 1.5;
~a.play;
```

### Stop with fade:

```supercollider
~a.stop;
```

### Free with fade:

```supercollider
~a.free;
```

### Query the input bus:

```supercollider
~a.bus(\sth);
```

### Monitor everything:

```supercollider
NdMSpace.current.dump;
```

---

# Live-Coding Example: Patch Stability

```supercollider
a = NdMSpace.enter;

// 1. Start main voice
~main = nd { |mod|
    SinOsc.ar(mod * 300 + 400) * 0.2
}.out(0).play;

// 2. Add modulation
~mod = nd {
    LFNoise2.ar(1).range(0.1, 3.0)
}.out(~main[\mod]).play;

// 3. Add another modulator
~mod1 = nd {
    LFPulse.ar(10)
}.out(~main[\mod]).play;

// 4. Redefine main voice — modulation continues
~main = nd { |mod|
    LFTri.ar(mod * 500 + 200) * 0.2
};

// 5. Replace modulator — bus index stays the same
~mod1 = nd {
    SinOsc.ar(8, 0, 0.1)
}.out(~main[\mod]);

// Cleanup
a.reset;
a.exit;
```

All modulation paths remain intact; no clicks, no re-routing.

---

# Error Handling (NdMError)

NdMSpace and NdM provide clean, readable error messages:

```supercollider
NdMError("NdM: rate mismatch (sig=ar, bus=kr)").throw;
```

Example:
If your function outputs audio but you route it to a control-rate bus,
the system reports a simple readable error without noisy stack traces.

---

# MIT License

```
MIT License

Copyright (c) 2025 piperauritum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

