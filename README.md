# NdMSpace — A Live-Coding Environment With Persistent, Bus-Stable NdM Nodes

*(for Live Coding in SuperCollider)*

NdMSpace provides a stable, persistent environment for managing **NdM** nodes as environment variables (`~key`), offering **bus-stable function replacement**, **per-instance fading**, **automatic reuse of argument buses**, and **safe namespace-level monitoring**.
It is designed for **live coding musicians** who constantly redefine running processes while keeping audio stable, without clicks, bus conflicts, or broken mappings.

NdMSpace also tracks connection order between nodes.
While the internal graph is marked dirty, execution order is not guaranteed.

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

### Early bus reference (pre-binding argument buses)

NdMSpace allows argument buses to be referenced *before* the corresponding
NdM node is defined.
Accessing an argument bus using the syntax:

```supercollider
~node[argName]
````

returns a `Bus` object and, if necessary, allocates it immediately.
If the node does not yet exist, NdMSpace creates a silent placeholder
associated with the given key.

This behavior is called **Early bus reference**.

The allocated bus is recorded in `NdMNameSpace` using the pair
*(node key, argument name)* and is reused automatically when the node
is later defined or redefined with `nd { ... }`.
As a result, routing destinations remain stable across deferred node
creation, live redefinition, and function switching.

Example:

```supercollider
a = NdMSpace.enter;

// The carrier does not exist yet.
// ~car[\frq] allocates and returns the argument bus early.
~mod = nd { SinOsc.ar(5).range(100, 400) }.out(~car[\frq]).play;

// The carrier is defined later.
// The argument \frq reuses the same bus automatically.
~car = nd { |frq| SinOsc.ar(frq, 0, 0.1) }.out([0, 1]).play;
```

Early bus reference makes it possible to write node connections
from either direction and is especially useful in live coding,
where modulators and carriers are often defined incrementally.


### Feedback loops and bus assignment

NdMSpace does not support feedback loops in its argument-bus graph.

If a feedback loop is formed between NdM nodes,
graph rebuilding is aborted and bus assignment is not applied.
A warning is printed to notify the user.

For the exact definition of “feedback loop” in NdMSpace
and recommended alternatives such as `InFeedback.ar`,
see **NdMSpace.schelp — Feedback loop restriction (bus assignment)**.

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

### **Output mapping rules (important)**

NdM defines explicit and predictable rules for mapping signals to output buses.

#### 1. Mono signal → multiple buses

If the function returns a mono signal and `.out` receives multiple buses:

```supercollider
~osc = nd { SinOsc.ar(100) }.out([0, 1]).play;
````

the same signal is written independently to each bus:

```supercollider
Out.ar(0, sig);
Out.ar(1, sig);
```

This is **not duplication via channel expansion**, and does not cause leakage
or unintended summing. The result is centered playback.

#### 2. Multi-channel signal → multiple buses

If the function returns an array and the number of channels matches
the number of buses:

```supercollider
~osc = nd { Pan2.ar(SinOsc.ar(100), 0) }.out([0, 1]).play;
```

each channel is mapped one-to-one to each bus, equivalent to standard
`Out.ar(bus, sig)` behavior in SuperCollider.

#### 3. Channel/bus count mismatch

Mappings where the number of signal channels does not match the number
of buses (except mono → N) are currently undefined and should be avoided.

#### 4. Bus objects

`.out` accepts integers, `Bus` objects, or arrays of either.
All buses are normalized internally before patching.

---

## 5. **Argument-rate inference from key names**

If a key ends with `_a` or `_k`:

```supercollider
~osc_k = nd { |freq, pan| ... };  // NdM(\osc_k) → all arguments are control-rate
```

This provides predictable DSP graphs during live performance.

---

## 6. **A reliable monitor: NdMSpace + NdMNameSpace**

`NdMSpace` offers:

* node lookup (`nodes`, `get`)
* tag grouping (`playTag`, `stopTag`, `freeTag`)
* space-wide teardown (`stopAll`, `freeAll`, `reset`, `clean`)
* persistent bus mapping (`NdMNameSpace`)

NdMSpace.dump and dumpKey are primary diagnostic tools
for observing dirty state and graph rebuilding results.

Monitoring is safe and does not leak resources.

---

# Quick Start

```supercollider
a = NdMSpace.enter;

// Simple sine
~osc = nd { |freq| SinOsc.ar(freq, 0, 0.1) }.out(0).play;

// Modulation routed via persistent bus
~lfo = nd { SinOsc.ar(1).range(200, 1200) }.out(~osc[\freq]).play;

// Update the function live — buses remain stable
~osc = nd { |freq| LFTri.ar(freq, 0, 0.1) };

// Cleaning up the space
a.freeAll;
a.reset;
a.exit;
```

## Alias style (optional, for live coding):

```supercollider
a = NdMSpace.enter;

~osc = nd { |freq| SinOsc.ar(freq, 0, 0.1) }.o(0).p;
~lfo = nd { SinOsc.ar(1).range(200, 1200) }.o(~osc[\freq]).p;

a.freeAll;
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

Short aliases: `.o`=out, `.f`=fade, `.t`=tag, `.p`=play, `.s`=stop.

### Query the input bus:

```supercollider
~a.bus(\sth);
```

### Monitor everything:

```supercollider
NdMSpace.dump;
```

---

# Live-Coding Example: Patch Stability

```supercollider
// 0. Enter NdMSpace
a = NdMSpace.enter;

// 1. Place the modulator
~mod0 = nd { LFNoise1.ar(2).range(0, 1) }.f(5).o(~osc[\mod]).t(\mod);

// 2. Add another modulator
~mod1 = nd { LFPulse.ar(10) }.f(5).o(~osc[\mod]).t(\mod);

// 3. Start main voice
~osc = nd { |mod| SinOsc.ar(mod * 500 + 500) * 0.2 }.f(5).o([0, 1]).p;

// 4. Start modulators
a.playTag(\mod);

// 5. Redefine main voice — modulation continues
~osc = nd { |mod| LFTri.ar(mod * 1000 + 1000) * 0.2 };

// 6. Replace a modulator — bus index stays the same
~mod1 = nd { SinOsc.ar(8, 0, 0.1) };

// 7. Stop the modulators
a.stopTag(\mod);

// 8. Cleanup
a.freeAll;
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

