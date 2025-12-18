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
			this.debugPost("[NdM.play] ignored: proxy is nil (already freed?) key=" ++ key.asString);
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
		if(doStart) {
			running = true;
			fork {
				this.apply;
			};
		} {
			this.debugPost("[APPLY] already running");
		};

		^this;
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

		// preemptable-wait temps (must be declared at block head)
		var remain;
		var step;
		var remain2;
		var step2;

		doLoop = true;

		while { doLoop } {

			genStart = reqGen;

			proxyLocal = proxy;
			if(proxyLocal.isNil) {
				this.debugPost("[APPLY] proxy is nil");
				running = false;
				doLoop = false;
			} {
				wantPlayLocal = wantPlay ? false;
				wantFreeLocal = wantFree ? false;

				fadeLocal = wantFade ? 0.0;
				if(fadeLocal < 0.0) {
					fadeLocal = 0.0;
				};

				switchLocal = wantSwitch ? 0.0;
				if(switchLocal < 0.0) {
					switchLocal = 0.0;
				};

				funcLocal = wantFunc ? func;
				funcChanged = proxyLocal.source.isNil || (appliedFunc != funcLocal);

				this.debugPost(
					"[APPLY] start gen=" ++ genStart.asString
					++ " wantPlay=" ++ wantPlayLocal.asString
					++ " wantFree=" ++ wantFreeLocal.asString
					++ " fade=" ++ fadeLocal.asString
					++ " sw=" ++ switchLocal.asString
					++ " funcChanged=" ++ funcChanged.asString
				);

				// 0) Free request has priority
				if(wantFreeLocal && (wantPlayLocal.not)) {

					// fade-out by VarLag if requested
					if(reqGen == genStart) {
						proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);
						proxyLocal.set(\ndmAmp, 0.0);
					};

					// Preemptable wait: check generation during fade-out.
					if(fadeLocal > 0.0) {
						remain = fadeLocal;
						step = 0.05;

						while { (remain > 0.0) && (reqGen == genStart) } {
							if(remain < step) {
								remain.wait;
								remain = 0.0;
							} {
								step.wait;
								remain = remain - step;
							};
						};
					} {
						if(switchLocal > 0.0) {
							if(reqGen == genStart) {
								proxyLocal.set(\ndmGate, 0, \switchDur, switchLocal);
							};

							remain2 = switchLocal;
							step2 = 0.05;

							while { (remain2 > 0.0) && (reqGen == genStart) } {
								if(remain2 < step2) {
									remain2.wait;
									remain2 = 0.0;
								} {
									step2.wait;
									remain2 = remain2 - step2;
								};
							};
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

						doLoop = false;
						running = false;
						this.debugPost("[APPLY] freed gen=" ++ genStart.asString);
					} {
						this.debugPost("[APPLY] abort free gen=" ++ genStart.asString
							++ " current=" ++ reqGen.asString);
					};

				} {

					// 1) Function switch (gate down -> swap source -> gate up)
					if(funcChanged) {
						if((switchLocal > 0.0) && proxyLocal.isPlaying && proxyLocal.source.notNil) {

							proxyLocal.set(\switchDur, switchLocal, \ndmGate, 0);
							switchLocal.wait;

							if(reqGen != genStart) {
								this.debugPost("[APPLY] abort after gateDown gen=" ++ genStart.asString
									++ " current=" ++ reqGen.asString);
							} {
								proxyFunc = this.makeProxyFunc;

								// Ensure amp is 0 BEFORE swapping source (avoid NodeProxy control carry-over race).
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

							// Ensure amp is 0 BEFORE swapping source (avoid NodeProxy control carry-over race).
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
					};

					// 2) Play/Stop apply (amp via VarLag in graph)
					if(reqGen == genStart) {

						if(wantPlayLocal) {
							proxyLocal.play;

							// Always push fade first (VarLag timebase).
							proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);

							// Critical: when funcChanged, force amp init to 0 then ramp to 1.
							if(funcChanged && (fadeLocal > 0.0)) {
								var srv;

								proxyLocal.set(\ndmAmp, 0.0);

								// Sync insurance: ensure the "amp=0" message is applied before we wait and ramp up.
								srv = this.getServer;
								if(srv.notNil) {
									try {
										srv.sync;
									} {
										// ignore (do not spam post window)
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

					// 3) Generation gate
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