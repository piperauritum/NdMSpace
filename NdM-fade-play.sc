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
			var gainEnv;

			// Read all argument buses into UGen values.
			values = this.readArgValues(busesLocal, argNamesLocal, argRatesLocal);
			signal = funcLocal.valueArray(values);

			// Guard: returning Bus (language object) is invalid for UGen graphs.
			// Convert the eventual "Bus * UGen" DoesNotUnderstandError into NdMError.
			if(signal.isKindOf(Bus)) {
				("[NdMWarn] WARN: [NdM] signal function returned a Bus. key=" ++ key
					++ "  This may cause a DoesNotUnderstand error later (e.g. Bus * UGen). "
					++ "Use In.ar(busIndex) / InFeedback.ar(busIndex) to read from a bus."
				).postln;
			};

			if(signal.isKindOf(Collection)) {
				if(signal.any { |v| v.isKindOf(Bus) }) {
					NdMError.new(
						"[NdM] invalid signal return (contains Bus). key=" ++ key
						++ "  hint: use In.ar(busIndex) / InFeedback.ar(busIndex) to read from a bus."
					).throw;
				};
			};

			if(signal.notNil) {
				// Determine output bus and its rate.
				outInfo = this.resolveOutBus(outbusLocal);
				busIndex = outInfo[0];
				busRate = outInfo[1];

				signalRate = signal.rate;

				// Smooth fade (play/stop/free) handled by VarLag.
				fadeEnv = VarLag.kr(ndmAmp, ndmFade);

				// gateEnv handles function-switch smoothing (via EnvGen).
				envShape = Env(
					[0, 0, 1, 0],
					[switchDur, switchDur, switchDur],
					'lin',
					2
				);

				// When switchDur == 0, EnvGen immediately outputs 1.0.
				gateEnv = EnvGen.kr(envShape, ndmGate);

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
				// gateEnv = 1.0;

				// signal = signal * fadeEnv * gateEnv;

				this.checkRateMatch(busRate, signalRate);
				this.writeSignalToBus(signal, busIndex, busRate);
			};

			0;
		};
	}

	// Start playback with VarLag-based fade-in and optional gate switch fade.
	play {
		var fadeDur;
		var fadeLocal;
		var switchLocal;

		if(proxy.isNil) {
			// Recreate NodeProxy so that a previously-freed NdM can be played again.
			proxy = Ndef(key);
			this.debugPost("[NdM.play] proxy recreated (was nil) key=" ++ key.asString);
		};

		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		switchLocal = switchDur;
		if(switchLocal.isNil) {
			switchLocal = 0.0;
		};
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		// Cancel pending free if user requests playback again.
		wantFree = false;

		wantPlay = true;
		wantFade = fadeLocal;
		wantSwitch = switchLocal;

		reqGen = reqGen + 1;
		this.applyKick;

		^this;
	}


	// Stop playback by fading amplitude to zero using VarLag.
	stop {
		var fadeDur;
		var fadeLocal;
		var switchLocal;

		if(proxy.isNil) {
			this.debugPost("[NdM.stop] ignored: proxy is nil (already freed?) key=" ++ key.asString);
			^this;
		};

		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		switchLocal = switchDur;
		if(switchLocal.isNil) {
			switchLocal = 0.0;
		};
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		wantPlay = false;
		wantFade = fadeLocal;
		wantSwitch = switchLocal;

		reqGen = reqGen + 1;
		this.applyKick;

		^this;
	}

	applyKick {
		var doStart;

		doStart = running.not;

		this.debugPost(
			"[APPLY-KICK] key=" ++ key.asString
			++ " running=" ++ running.asString
			++ " reqGen=" ++ reqGen.asString
			++ " doStart=" ++ doStart.asString
		);

		if(doStart) {
			running = true;

			this.debugPost(
				"[APPLY-KICK] start fork key=" ++ key.asString
				++ " running=" ++ running.asString
				++ " reqGen=" ++ reqGen.asString
			);

			fork {
				{
					this.debugPost("[APPLY-FORK] enter key=" ++ key.asString
						++ " reqGen=" ++ reqGen.asString);

					this.apply;

					this.debugPost("[APPLY-FORK] exit key=" ++ key.asString
						++ " reqGen=" ++ reqGen.asString);
				}.protect { |err|
					if(err.notNil) {
						this.debugPost("[APPLY-FORK] cleanup after ERROR key=" ++ key.asString
							++ " err=" ++ err.asString);
					} {
						this.debugPost("[APPLY-FORK] cleanup (no error) key=" ++ key.asString
							++ " reqGen=" ++ reqGen.asString);
					};

					// Always clear: apply may crash or may forget to flip it back.
					running = false;

					// Safe to clear even if not set; prevents leakage across failures.
					NdM.clearBuildingKey;
				};
			};
		} {
			this.debugPost(
				"[APPLY] already running key=" ++ key.asString
				++ " running=" ++ running.asString
				++ " reqGen=" ++ reqGen.asString
			);
		};

		^this;
	}

	applySnapshot {
		var xr;
		var proxyLocal;
		var fadeLocal;
		var switchLocal;
		var wantPlayLocal;
		var wantFreeLocal;
		var funcLocal;
		var funcChanged;

		xr = IdentityDictionary.new;

		proxyLocal = proxy;

		wantPlayLocal = if(wantPlay.isNil) { false } { wantPlay };
		wantFreeLocal = if(wantFree.isNil) { false } { wantFree };

		fadeLocal = if(wantFade.isNil) { 0.0 } { wantFade };
		if(fadeLocal < 0.0) {
			fadeLocal = 0.0;
		};

		switchLocal = if(wantSwitch.isNil) { 0.0 } { wantSwitch };
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		funcLocal = if(wantFunc.isNil) { func } { wantFunc };

		funcChanged = false;
		if(proxyLocal.notNil) {
			funcChanged = proxyLocal.source.isNil || (appliedFunc != funcLocal);
		};

		xr.put(\proxy, proxyLocal);
		xr.put(\wantPlay, wantPlayLocal);
		xr.put(\wantFree, wantFreeLocal);
		xr.put(\fade, fadeLocal);
		xr.put(\switchDur, switchLocal);
		xr.put(\func, funcLocal);
		xr.put(\funcChanged, funcChanged);

		^xr;
	}

	apply {
		var doLoop;
		var genStart;
		var proxyLocal;
		var fadeLocal;
		var switchLocal;
		var wantPlayLocal;
		var wantFreeLocal;
		var funcLocal;
		var funcChanged;
		var proxyFunc;
		var monitor1;
		var snap;

		// preemptable-wait temps (must be declared at block head)
		// removed: remain/step/remain2/step2 (handled by applyWaitGen)

		// formerly mid-block var in old apply
		var srv;

		doLoop = true;

		while { doLoop } {

			genStart = reqGen;

			proxyLocal = proxy;
			if(proxyLocal.isNil) {
				this.debugPost("[APPLY] proxy is nil");
				running = false;
				doLoop = false;
			} {
				snap = this.applySnapshot;

				proxyLocal = snap.at(\proxy);
				if(proxyLocal.isNil) {
					this.debugPost("[APPLY] proxy is nil");
					running = false;
					doLoop = false;
				} {
					wantPlayLocal = snap.at(\wantPlay);
					wantFreeLocal = snap.at(\wantFree);
					fadeLocal = snap.at(\fade);
					switchLocal = snap.at(\switchDur);
					funcLocal = snap.at(\func);
					funcChanged = snap.at(\funcChanged);

					this.debugPost(
						"[APPLY] start gen=" ++ genStart.asString
						++ " wantPlay=" ++ wantPlayLocal.asString
						++ " wantFree=" ++ wantFreeLocal.asString
						++ " fade=" ++ fadeLocal.asString
						++ " sw=" ++ switchLocal.asString
						++ " funcChanged=" ++ funcChanged.asString
					);
				};

				// A) free (highest priority)
				if(wantFreeLocal && (wantPlayLocal.not)) {
					this.applyHandleFree(
						genStart,
						proxyLocal,
						fadeLocal,
						switchLocal
					);
				} {
					// B) function switch (gate down -> swap -> gate up)
					if(funcChanged) {
						this.applyHandleFuncSwitch(genStart, proxyLocal, fadeLocal, switchLocal, funcLocal);
					};

					// C) play/stop
					this.applyHandlePlayStop(genStart, proxyLocal, fadeLocal, switchLocal, funcChanged, srv);

					// generation gate
					if(reqGen != genStart) {
						// rerun latest
					} {
						doLoop = false;
					};

					if(doLoop.not) {
						running = false;
						this.debugPost("[APPLY] done gen=" ++ genStart.asString);
					};
				};
			};
		};

		^this;
	}

	applyWaitGen { |genStart, dur, step|
		var remain;
		var stepLocal;

		remain = dur ? 0.0;
		if(remain < 0.0) {
			remain = 0.0;
		};

		stepLocal = step ? 0.05;
		if(stepLocal <= 0.0) {
			stepLocal = 0.05;
		};

		while { (remain > 0.0) && (reqGen == genStart) } {
			if(remain < stepLocal) {
				remain.wait;
				remain = 0.0;
			} {
				stepLocal.wait;
				remain = remain - stepLocal;
			};
		};

		^this;
	}

	applyHandleFree { |genStart, proxyLocal, fadeLocal, switchLocal|
		var monitor1;

		// fade-out by VarLag if requested
		if(reqGen == genStart) {
			proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);
			proxyLocal.set(\ndmAmp, 0.0);
		};

		// Preemptable wait: check generation during fade-out.
		if(fadeLocal > 0.0) {
			this.applyWaitGen(genStart, fadeLocal, 0.05);
		} {
			if(switchLocal > 0.0) {
				if(reqGen == genStart) {
					proxyLocal.set(\ndmGate, 0, \switchDur, switchLocal);
				};
				this.applyWaitGen(genStart, switchLocal, 0.05);
			};
		};

		if(reqGen == genStart) {
			if(proxy.notNil) {
				proxy.clear;
				proxy = nil;
			};

			if(argBuses.notNil) {
				argBuses = nil;
			};

			monitor1 = NdMNameSpace.instance;
			if(monitor1.notNil) {
				monitor1.unregister(key, this);
			};

			if(key.notNil) {
				instances.removeAt(key);
			};

			wantFree = false;
			wantPlay = false;
			appliedFunc = nil;

			running = false;
			this.debugPost("[APPLY] freed gen=" ++ genStart.asString);
		} {
			this.debugPost("[APPLY] abort free gen=" ++ genStart.asString
				++ " current=" ++ reqGen.asString);
		};

		^this;
	}

	applyHandleFuncSwitch { |genStart, proxyLocal, fadeLocal, switchLocal, funcLocal|
		var proxyFunc;

		if((switchLocal > 0.0) && proxyLocal.isPlaying && proxyLocal.source.notNil) {

			proxyLocal.set(\switchDur, switchLocal, \ndmGate, 0);
			switchLocal.wait;

			if(reqGen != genStart) {
				this.debugPost("[APPLY] abort after gateDown gen=" ++ genStart.asString
					++ " current=" ++ reqGen.asString);
			} {
				proxyFunc = this.makeProxyFunc;

				// Ensure amp is 0 BEFORE swapping source
				proxyLocal.set(
					\ndmFade, fadeLocal,
					\switchDur, switchLocal,
					\ndmGate, 1,
					\ndmAmp, 0.0
				);

				proxyLocal.source = proxyFunc;
				proxyLocal.set(\switchDur, switchLocal, \ndmGate, 1);
				appliedFunc = funcLocal;
			};

		} {
			proxyFunc = this.makeProxyFunc;

			proxyLocal.set(
				\ndmFade, fadeLocal,
				\switchDur, switchLocal,
				\ndmGate, 1,
				\ndmAmp, 0.0
			);

			proxyLocal.source = proxyFunc;
			proxyLocal.set(\switchDur, switchLocal, \ndmGate, 1);
			appliedFunc = funcLocal;
		};

		^this;
	}

	applyHandlePlayStop { |genStart, proxyLocal, fadeLocal, switchLocal, funcChanged, srv|
		if(reqGen == genStart) {

			if((wantPlay ? false)) {
				proxyLocal.play;

				// Always push fade first (VarLag timebase).
				proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);

				// Critical: when funcChanged, force amp init to 0 then ramp to 1.
				if(funcChanged && (fadeLocal > 0.0)) {

					proxyLocal.set(\ndmAmp, 0.0);

					// Sync insurance
					srv = this.getServer;
					if(srv.notNil) {
						try {
							srv.sync;
						} {
							// ignore
						};
					};

					NdM.fadeGap.wait;

					if(reqGen == genStart) {
						proxyLocal.set(\ndmAmp, 1.0);
						this.debugPost("[APPLY] ampUp(init) gen=" ++ genStart.asString);
					} {
						this.debugPost("[APPLY] abort before ampUp(init) gen=" ++ genStart.asString
							++ " current=" ++ reqGen.asString);
					};

				} {
					proxyLocal.set(\ndmAmp, 1.0);
					this.debugPost("[APPLY] ampUp gen=" ++ genStart.asString);
				};

			} {
				proxyLocal.set(\ndmFade, fadeLocal, \ndmAmp, 0.0, \switchDur, switchLocal, \ndmGate, 1);
				this.debugPost("[APPLY] ampDown gen=" ++ genStart.asString);
			};
		};

		^this;
	}

	// Free the instance, with optional fade-out or switch-duration ramp.
	free {
		var fadeDur;
		var fadeLocal;
		var switchLocal;

		if(proxy.isNil) {
			this.debugPost("[NdM.free] ignored: proxy is nil (already freed?) key=" ++ key.asString);
			^this;
		};

		// sanitize fade
		fadeDur = fadetime;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		// sanitize switch
		switchLocal = switchDur;
		if(switchLocal.isNil) {
			switchLocal = 0.0;
		};
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		// request free (single-writer: apply does the actual cleanup)
		wantFree = true;
		wantPlay = false;
		wantFade = fadeLocal;
		wantSwitch = switchLocal;

		reqGen = reqGen + 1;
		this.applyKick;

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