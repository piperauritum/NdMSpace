# NdMSpace

*(for Live Coding in SuperCollider)*

NdMSpace provides a stable and predictable environment for building signal networks in SuperCollider.
Its purpose is to make live coding with shared parameters safe, reusable, and easy to reason about,
without requiring manual bus management or careful control of node execution order.

This document describes the behavior and advantages of NdMSpace as implemented in the current version.

---

## What NdMSpace provides

NdMSpace manages connections between nodes so that:

- multiple nodes can safely control the same parameter
- parameter connections survive node redefinition
- execution order is kept consistent automatically
- common pitfalls of shared buses in SuperCollider are avoided

All of this is handled by NdMSpace itself.
Users write ordinary NdM code and do not need to manage buses or node order explicitly.

---

## Argument buses: additive and stable

### Argument buses are additive

In NdMSpace, each argument of an NdM node is backed by its own *argument bus*.

When more than one node writes to the same argument bus, their signals are **added together**.
No writer overwrites another.

This allows multiple modulators to contribute to a single parameter naturally.

```supercollider
a = NdMSpace.enter;
a.defaultOut = [0, 1];

~osc = nd { |freq| SinOsc.ar(freq, 0, 0.2) }.play;

~mod1 = nd { SinOsc.ar(0.3).range(100, 400) }.out(~osc[\freq]).play;
~mod2 = nd { LFNoise0.ar(1).range(100, 2000) }.out(~osc[\freq]).play;

// Cleaning up the space
a.freeAll;
a.reset;
a.exit;
````

This behavior is consistent for both control-rate and audio-rate arguments.

---

### Argument buses persist across redefinition

Argument buses in NdMSpace are associated with a pair of:

* the NdM key
* the argument name

As long as these remain the same, the same argument bus is reused.

Redefining a node does **not** break existing connections.
Modulators continue to write to the same argument bus even if the main node is stopped,
redefined, or recreated.

```supercollider
~osc = nd { |freq| SinOsc.ar(freq, 0, 0.2) }.play;
~mod = nd { SinOsc.ar(0.3).range(100, 400) }.out(~osc[\freq]).play;

~osc.stop;

// redefine ~osc
// ~mod still controls freq
~osc = nd { |freq| Saw.ar(freq, 0.2) }.play;
```

This makes live coding safer and more predictable, since connections do not silently disappear.

---

## Audio-rate and control-rate support

NdMSpace supports both audio-rate and control-rate argument buses.

The rate of an argument is determined automatically from:

* argument name suffixes (`_a`, `_k`)
* key name suffixes
* explicit argument annotations

Internally, NdMSpace reads each argument bus using the appropriate mechanism for its rate.
From the user’s perspective, this means:

* audio-rate modulation works as expected
* control-rate parameters can be shared freely
* no manual rate handling is required in normal usage

```supercollider
~osc = nd { |mod_k| SinOsc.ar(200 + mod_k, 0, 0.1) }.play;

~lfo = nd { SinOsc.kr(5).range(-50, 50) }.out(~osc[\mod_k]).play;
```

---

## Execution order safety

### NdMSpace maintains a correct execution order

In plain SuperCollider, reading from a shared bus can depend on the execution order of nodes.
This can lead to subtle and confusing behavior when nodes are added or redefined.

NdMSpace prevents this issue.

Whenever connections between nodes change, NdMSpace automatically adjusts the execution order so that:

* nodes writing to an argument bus run before
* nodes reading from that bus

This happens transparently.
As a result, argument buses behave as if all contributing writers have already been applied
when the reader runs.

In normal NdMSpace usage, users do not need to think about node order at all.

---

### Feedback loops and bus assignment

NdMSpace does not support feedback loops in its argument-bus graph.

If a feedback loop is formed between NdM nodes,
graph rebuilding is aborted and bus assignment is not applied.
A warning is printed to notify the user.

For the exact definition of “feedback loop” in NdMSpace
and recommended alternatives such as `InFeedback.ar`,
see **NdMSpace.schelp — Feedback loop restriction (bus assignment)**.

---

## Redefinition and live coding

NdMSpace is designed for live coding workflows.

* Nodes can be stopped and redefined freely
* Argument buses remain stable
* Existing modulators keep working
* Execution order is re-evaluated automatically when needed

Features such as `fade`, `play`, `stop`, and switching between definitions
build on top of these guarantees and do not invalidate existing connections.

---

## Output routing and argument buses

Argument buses and output routing serve different roles in NdMSpace.

* **Argument buses**

  * additive
  * shared between multiple nodes
  * managed automatically
* **Output routing (`out`)**

  * uses raw bus indices or arrays
  * follows stricter rules
  * acts as a boundary for graph updates

If `out` is not specified explicitly for a node, NdMSpace uses the current
`defaultOut` value as its output target.
Array mapping rules apply in the same way as with an explicit `out`
(see NdM out specification).

This separation keeps parameter modulation flexible while keeping output routing explicit and safe.

---

## Summary

NdMSpace provides:

* additive, persistent argument buses
* support for audio-rate and control-rate parameters
* automatic execution order management
* safe live redefinition without broken connections

These features allow users to focus on musical structure and interaction,
rather than low-level bus and node management.

---

# Quick Start

```supercollider
a = NdMSpace.enter;
a.defaultOut = [0, 1];   // default output buses for nodes without explicit out

// Simple sine
~osc = nd { |freq| SinOsc.ar(freq, 0, 0.1) }.play;

// Modulation routed via persistent argument bus
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
a.defaultOut = [0, 1];

~osc = nd { |freq| SinOsc.ar(freq, 0, 0.1) }.p;
~lfo = nd { SinOsc.ar(1).range(200, 1200) }.o(~osc[\freq]).p;

a.freeAll;
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

---

# Live-Coding Example: Patch Stability

```supercollider
// 0. Enter NdMSpace
a = NdMSpace.enter;
a.defaultOut = [0, 1];   // set default output buses for this space

// 1. Place the modulator
~mod0 = nd { LFNoise1.ar(2).range(0, 1) }.f(5).o(~osc[\mod]).t(\mod);

// 2. Add another modulator
~mod1 = nd { LFPulse.ar(10) }.f(5).o(~osc[\mod]).t(\mod);

// 3. Start main voice
~osc = nd { |mod| SinOsc.ar(mod * 500 + 500) * 0.2 }.f(5).p;

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
[NdMError] ERROR: NdM: rate mismatch (sig=kr, bus=ar)
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

