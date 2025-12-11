+ NdM {

	// Build a ProxySynthDef capturing the current arg buses, rates, and outbus.
	makeProxyFunc {
		var busesLocal;
		var argNamesLocal;
		// Cached references to argument metadata for building the UGen graph.
		var funcLocal;
		var outbusLocal;
		// Per-argument rate table controlling kr/ar selection.
		var argRatesLocal;
		// Index of fadeBus used for exposing fade envelope externally.
		var fadeBusIndexLocal;
		// Debug ID allowing identification of each generated Synth instance.
		var synthDebugIdLocal;
		var fadeEnv;
		var gateEnv;
		var gainEnv;
		var envShape;
		var switchDurLocal;

		busesLocal = argBuses;
		argNamesLocal = argNames;
		funcLocal = func;
		outbusLocal = outbus;
		argRatesLocal = argRates;

		// Use existing fadeBus if available, otherwise mark inactive (-1).
		if(fadeBus.notNil) {
			fadeBusIndexLocal = fadeBus.index;
		} {
			fadeBusIndexLocal = -1;
		};

		// Unique ID per Synth instantiation for debugging/tracking.
		synthDebugIdLocal = UniqueID.next;
		this.debugPost(
			"[NdM.makeProxyFunc] key: " ++ key
			++ "  synthDebugIdLocal: " ++ synthDebugIdLocal
		);

		// Resolve switch duration for gate-based crossfade handling.
		switchDurLocal = switchDur;
		if(switchDurLocal.isNil) {
			switchDurLocal = 0.0;
		};

		// Return a SynthDef function receiving control params.
		^{
			|ndmAmp = 0, ndmFade = 0, ndmGate = 1, switchDur = 0|
			// |ndmAmp = 0, ndmFade = 0|
			var values;
			var signal;
			var outInfo;
			var busIndex;
			var busRate;
			var signalRate;
			var fadeEnv;
			var synthDebugId;
			var gateEnv;

			// Read all argument buses into UGen values.
			values = this.readArgValues(busesLocal, argNamesLocal, argRatesLocal);
			signal = funcLocal.valueArray(values);

			if(signal.notNil) {
				// Determine output bus and its rate.
				outInfo = this.resolveOutBus(outbusLocal);
				busIndex = outInfo[0];
				busRate = outInfo[1];

				signalRate = signal.rate;

				// Smooth fade (play/stop/free) handled by VarLag.
				fadeEnv = VarLag.kr(ndmAmp, ndmFade);

				// gateEnv handles function-switch smoothing (via EnvGen).
				if(switchDurLocal > 0.0) {
					// Gate envelope: hold at 1 during sustain; ramps around it.
					envShape = Env(
						[0, 0, 1, 0],
						[switchDurLocal, switchDurLocal, switchDurLocal],
						'lin',
						2
					);
					gateEnv = EnvGen.kr(envShape, ndmGate);
				} {
					// No switching fade → gateEnv is a constant 1.
					gateEnv = 1.0;
				};

				// Combine fade and switch envelopes for final multiplier.
				gainEnv = fadeEnv * gateEnv;
				signal = signal * gainEnv;

				// Optional debug polling of envelopes.
				this.conditionalPoll(fadeEnv, pollFreq, "[NdM] fadeEnv");
				this.conditionalPoll(gateEnv, pollFreq, "[NdM] gateEnv");


				// Emit unique Synth ID for debugging.
				synthDebugId = DC.kr(synthDebugIdLocal);

				this.conditionalPoll(synthDebugId, pollFreq, "[NdM] synthId");
				this.conditionalPoll(fadeEnv, pollFreq, "[NdM] fadeEnv");

				// Write fade envelope to control bus if configured.
				if(fadeBusIndexLocal >= 0) {
					Out.kr(fadeBusIndexLocal, fadeEnv);
				};

				// signal = signal * fadeEnv;
				// (Legacy placeholder: gateEnv overridden to 1.0 below)
				gateEnv = 1.0;

				// signal = signal * fadeEnv * gateEnv;

				this.checkRateMatch(busRate, signalRate);
				this.writeSignalToBus(signal, busIndex, busRate);
			};

			0;
		};
	}

	// Start playback with VarLag-based fade-in and optional gate switch fade.
	play {
		var proxyLocal;
		var proxyFunc;
		var serverLocal;
		var threadLocal;
		var fadeDur;
		var fadeLocal;
		var switchDurLocal;

		proxyLocal = proxy;
		if(proxyLocal.isNil) {
			this.debugPost("[NdM.play] key: " ++ key ++ " proxy is nil");
			^this;
		};

		serverLocal = this.getServer;
		threadLocal = thisThread;

		// For first run, build proxy.source; afterwards reuse existing one.
		if(hasFadeInit.not) {
			this.debugPost("[NdM.play] build proxy.source (first time)");
			proxyFunc = this.makeProxyFunc;
			proxyLocal.source = proxyFunc;
		} {
			this.debugPost("[NdM.play] reuse existing proxy.source");
		};

		// Retrieve user fade duration (enforced to non-negative).
		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		// Resolve gate-switch duration.
		switchDurLocal = switchDur;
		if(switchDurLocal.isNil) {
			switchDurLocal = 0.0;
		};

		this.debugPost(
			"[NdM.play] key: " ++ key
			++ "  fadeLocal: " ++ fadeLocal
			++ "  hasFadeInit: " ++ hasFadeInit
			++ "  thread: " ++ threadLocal.class.asString
		);

		// First-time VarLag fade-in: 0 → 1 over fadeLocal seconds.
		if(fadeLocal > 0.0) {
			if(hasFadeInit.not) {
				this.debugPost(
					"[NdM.play] branch: fade-first  "
					++ "set ndmFade=" ++ fadeLocal
					++ " ndmAmp=0.0"
				);

				proxyLocal.set(
					\ndmFade, fadeLocal,
					\ndmAmp, 0.0,
					\ndmGate, 1,
					\switchDur, switchDurLocal
				);
				proxyLocal.play;

				// Server:sync only when inside a Routine.
				if(
					serverLocal.notNil
					&& { threadLocal.isKindOf(Routine) }
				) {
					this.debugPost("[NdM.play] calling Server:sync");
					try {
						serverLocal.sync;
					} {
						this.debugPost("[NdM.play] Server:sync failed (ignored)");
					};
				} {
					this.debugPost("[NdM.play] skip Server:sync (not in Routine)");
				};

				// After a small delay, ramp ndmAmp from 0 to 1 (VarLag handles smoothing).
				fork {
					this.debugPost(
						"[NdM.play] fork: wait fadeGap="
						++ NdM.fadeGap
					);
					NdM.fadeGap.wait;
					this.debugPost("[NdM.play] fork: set ndmAmp=1.0");
					proxyLocal.set(
						\ndmAmp, 1.0,
						\ndmGate, 1,
						\switchDur, switchDurLocal
					);
				};

				hasFadeInit = true;
			} {
				// Subsequent plays: only smooth from current value → 1.
				this.debugPost(
					"[NdM.play] branch: fade-again  "
					++ "set ndmFade=" ++ fadeLocal
					++ " ndmAmp=1.0"
				);

				proxyLocal.play;
				proxyLocal.set(
					\ndmFade, fadeLocal,
					\ndmAmp, 1.0,
					\ndmGate, 1,
					\switchDur, switchDurLocal
				);

				if(
					serverLocal.notNil
					&& { threadLocal.isKindOf(Routine) }
				) {
					this.debugPost("[NdM.play] calling Server:sync");
					try {
						serverLocal.sync;
					} {
						this.debugPost("[NdM.play] Server:sync failed (ignored)");
					};
				} {
					this.debugPost("[NdM.play] skip Server:sync (not in Routine)");
				};
			};
		} {
			// Play with no fade: simply set full amplitude.
			this.debugPost("[NdM.play] branch: no-fade");

			proxyLocal.play;
			proxyLocal.set(
				\ndmFade, 0.0,
				\ndmAmp, 1.0,
				\ndmGate, 1,
				\switchDur, switchDurLocal
			);

			if(
				serverLocal.notNil
				&& { threadLocal.isKindOf(Routine) }
			) {
				this.debugPost("[NdM.play] calling Server:sync");
				try {
					serverLocal.sync;
				} {
					this.debugPost("[NdM.play] Server:sync failed (ignored)");
				};
			} {
				this.debugPost("[NdM.play] skip Server:sync (not in Routine)");
			};
		};

		^this;
	}

	// Stop playback by fading amplitude to zero using VarLag.
	stop {
		var proxyLocal;
		var fadeDur;
		var fadeLocal;
		var switchDurLocal;

		proxyLocal = proxy;
		if(proxyLocal.isNil) {
			this.debugPost("[NdM.stop] proxy is nil");
			^this;
		};

		// Retrieve fade duration (forced non-negative).
		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		// Resolve gate-switch duration.
		switchDurLocal = switchDur;
		if(switchDurLocal.isNil) {
			switchDurLocal = 0.0;
		};

		if(fadeLocal > 0.0) {
			// Smooth fade-out: 1 → 0 using VarLag.
			this.debugPost(
				"[NdM.stop] fadeLocal=" ++ fadeLocal
			);

			proxyLocal.set(
				\ndmFade, fadeLocal,
				\ndmAmp, 0.0,
				\ndmGate, 1,
				\switchDur, switchDurLocal
			);
		} {
			// Immediate mute: no smoothing.
			this.debugPost("[NdM.stop] no fade (immediate mute)");

			proxyLocal.set(
				\ndmFade, 0.0,
				\ndmAmp, 0.0,
				\ndmGate, 1,
				\switchDur, switchDurLocal
			);
		};

		^this;
	}

	// Free the instance, with optional fade-out or switch-duration ramp.
	free {
		var proxyLocal;
		var monitor1;
		var fadeDur;
		var fadeLocal;
		var switchDurLocal;

		proxyLocal = proxy;

		// Determine fade-out duration.
		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		// Resolve switch ramp duration.
		switchDurLocal = switchDur;
		if(switchDurLocal.isNil) {
			switchDurLocal = 0.0;
		};

		// (1) Fade-out using VarLag then free.
		if(proxyLocal.notNil && (fadeLocal > 0.0)) {
			this.debugPost(
				"[NdM.free] fadeLocal=" ++ fadeLocal
				++ " (VarLag fade-out)"
			);

			proxyLocal.set(
				\ndmFade, fadeLocal,
				\ndmAmp, 0.0,
				\ndmGate, 1,
				\switchDur, switchDurLocal
			);

			// Deferred free scheduled after fade-out time.
			SystemClock.sched(fadeLocal, {
				var monLocal;

				if(proxy.notNil) {
					proxy.clear;
					proxy = nil;
				};

				if(argBuses.notNil) {
					// argBuses.do { |bus|
					// 	if(bus.notNil) {
					// 		bus.free;
					// 	};
					// };
					argBuses = nil;
				};

				monLocal = NdMNameSpace.instance;
				if(monLocal.notNil) {
					monLocal.unregister(key, this);
				};

				if(key.notNil) {
					instances.removeAt(key);
				};

				nil;
			});

			^this;
		};

		// (2) Gate-based fade (switchDurLocal).
		if(proxyLocal.notNil && (switchDurLocal > 0.0)) {
			this.debugPost(
				"[NdM.free] gate fade switchDur="
				++ switchDurLocal
			);

			proxyLocal.set(
				\ndmGate, 0,
				\switchDur, switchDurLocal
			);

			SystemClock.sched(switchDurLocal, {
				var monLocal;

				if(proxy.notNil) {
					proxy.clear;
					proxy = nil;
				};

				if(argBuses.notNil) {
					// argBuses.do { |bus|
					// 	if(bus.notNil) {
					// 		bus.free;
					// 	};
					// };
					argBuses = nil;
				};

				monLocal = NdMNameSpace.instance;
				if(monLocal.notNil) {
					monLocal.unregister(key, this);
				};

				if(key.notNil) {
					instances.removeAt(key);
				};

				nil;
			});

			^this;
		};

		// (3) Immediate free with no fade.
		this.debugPost("[NdM.free] immediate free (no fade, no gate)");

		if(proxyLocal.notNil) {
			proxyLocal.clear;
			proxy = nil;
		};

		if(argBuses.notNil) {
			// argBuses.do { |bus|
			// 	if(bus.notNil) {
			// 		bus.free;
			// 	};
			// };
			argBuses = nil;
		};

		monitor1 = NdMNameSpace.instance;
		if(monitor1.notNil) {
			monitor1.unregister(key, this);
		};

		if(key.notNil) {
			instances.removeAt(key);
		};

		^this;
	}

	// Unified fade getter/setter for NdM’s own fade duration (VarLag).
	fade { |value|
		var fadeValue;
		var proxyLocal;

		proxyLocal = proxy;

		// Getter mode when value is nil.
		if(value.isNil) {
			^fadetime;
		};

		// Setter mode: sanitize fade duration.
		fadeValue = value;
		if(fadeValue.isNil) {
			fadeValue = 0.0;
		};
		if(fadeValue < 0.0) {
			fadeValue = 0.0;
		};

		fadetime = fadeValue;

		// NodeProxy fadeTime is disabled (always zero).
		if(proxyLocal.notNil) {
			this.debugPost(
				"[NdM.fade] key: " ++ key
				++ "  fadetime: " ++ fadeValue
				++ "  proxy.fadeTime(before): " ++ proxyLocal.fadeTime
			);

			proxyLocal.fadeTime = 0.0;
		};

		^this;
	}

	// Property-style fade setter (same semantics as fade()).
	fade_ { |value|
		var fadeValue;
		var proxyLocal;
		var proxyFade;

		fadeValue = value;
		if(fadeValue.isNil) {
			fadeValue = 0.0;
		};
		if(fadeValue < 0.0) {
			fadeValue = 0.0;
		};

		fadetime = fadeValue;

		// Debug output only; does not affect NodeProxy fade logic.
		proxyLocal = proxy;
		if(proxyLocal.notNil) {
			proxyFade = proxyLocal.fadeTime;
			this.debugPost("[NdM.fade_] key: " ++ key
				++ "  fadetime: " ++ fadetime
				++ "  proxy.fadeTime(before): " ++ proxyFade);
		} {
			this.debugPost("[NdM.fade_] key: " ++ key
				++ "  fadetime: " ++ fadetime
				++ "  proxy: nil");
		};

		^this;
	}

	// === UNUSED ===
	// Allocate a dedicated fadeBus if none exists (exposed fade envelope).
	ensureFadeBus { |serverIn|
		var serverLocal;
		var busLocal;

		serverLocal = serverIn;
		if(serverLocal.isNil) {
			serverLocal = this.getServer;
		};

		if(fadeBus.isNil) {
			busLocal = Bus.control(serverLocal, 1);
			fadeBus = busLocal;
			serverLocal.sendMsg(\c_set, busLocal.index, 0.0);
		};
	}
}