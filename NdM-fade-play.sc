+ NdM {

	// Build a ProxySynthDef capturing the current arg buses, rates, and outbus.
	makeProxyFunc {
		var ctxLocal;

		ctxLocal = this.prepareMakeProxyContext;

		^this.makeProxySynthDefFunc(ctxLocal);
	}

	makeProxySynthDefFunc { |ctxLocal|
		var busesLocal;
		var argNamesLocal;
		var funcLocal;
		var outbusLocal;
		var argRatesLocal;
		var fadeBusIndexLocal;
		var synthDebugIdLocal;
		var switchDurLocal;
		var ndmLocal;

		busesLocal = ctxLocal.at(\busesLocal);
		argNamesLocal = ctxLocal.at(\argNamesLocal);
		funcLocal = ctxLocal.at(\funcLocal);
		outbusLocal = ctxLocal.at(\outbusLocal);
		argRatesLocal = ctxLocal.at(\argRatesLocal);
		fadeBusIndexLocal = ctxLocal.at(\fadeBusIndexLocal);
		synthDebugIdLocal = ctxLocal.at(\synthDebugIdLocal);
		switchDurLocal = ctxLocal.at(\switchDurLocal);
		ndmLocal = ctxLocal.at(\ndmLocal);

		^this.buildProxyUgenFunc(
			busesLocal,
			argNamesLocal,
			argRatesLocal,
			funcLocal,
			outbusLocal,
			fadeBusIndexLocal,
			synthDebugIdLocal,
			switchDurLocal,
			ndmLocal
		);
	}

	buildNdmFadeEnv { |ndmAmp, ndmFade|
		var xr;

		xr = VarLag.kr(ndmAmp, ndmFade);

		^xr;
	}

	buildNdmGateEnv { |ndmGate, switchDur|
		var xr;
		var envShape;

		envShape = Env(
			[0, 0, 1, 0],
			[switchDur, switchDur, switchDur],
			'lin',
			2
		);

		xr = EnvGen.kr(envShape, ndmGate);

		^xr;
	}

	buildProxyUgenFunc {
		|busesLocal, argNamesLocal, argRatesLocal, funcLocal, outbusLocal,
		fadeBusIndexLocal, synthDebugIdLocal, switchDurLocal, ndmLocal|

		^{
			|ndmAmp = 0, ndmFade = 0, ndmGate = 1, switchDur = 0|
			var argValues;
			var signal;
			var outInfo;
			var busIndex;
			var busRate;
			var signalRate;
			var fadeEnv;
			var synthDebugId;
			var gateEnv;
			var gainEnv;
			var envShape;

			ndmLocal.postReadArgMeta(busesLocal, argNamesLocal, argRatesLocal);

			argValues = ndmLocal.readArgValuesFromBuses(busesLocal, argNamesLocal, argRatesLocal);

			signal = this.evalProxySignalFromArgs(ndmLocal, funcLocal, argValues);

			if(signal.notNil) {
				this.writeProxySignalToBus(
					ndmLocal,
					signal,
					outbusLocal,
					fadeBusIndexLocal,
					synthDebugIdLocal,
					ndmAmp,
					ndmFade,
					ndmGate,
					switchDur
				);
			};

			0;
		};
	}

	evalProxySignalFromArgs { |ndmLocal, funcLocal, argValues|
		var signalLocal;

		ndmLocal.postMakeProxyDiag(funcLocal, argValues);

		signalLocal = funcLocal.valueArray(argValues);

		ndmLocal.debugPost("[DBG][MP] signal.class=" ++ signalLocal.class.asString);

		ndmLocal.validateSignalReturn(signalLocal);

		^signalLocal;
	}

	writeProxySignalToBus {
		|ndmLocal, signalIn, outbusLocal, fadeBusIndexLocal, synthDebugIdLocal,
		ndmAmp, ndmFade, ndmGate, switchDur|
		var signalLocal;
		var outInfo;
		var busIndex;
		var busRate;
		var signalRate;
		var fadeEnv;
		var gateEnv;
		var gainEnv;
		var synthDebugId;

		signalLocal = signalIn;

		outInfo = ndmLocal.resolveOutBus(outbusLocal);
		busIndex = outInfo[0];
		busRate = outInfo[1];

		ndmLocal.debugPost(
			"[DBG][ARGBUS][W] key=" ++ ndmLocal.key.asString
			++ " outbusLocal.class=" ++ outbusLocal.class.asString
			++ " busIndex=" ++ busIndex.asString
			++ " busRate=" ++ busRate.asString
		);

		signalRate = signalLocal.rate;

		fadeEnv = this.buildNdmFadeEnv(ndmAmp, ndmFade);
		gateEnv = this.buildNdmGateEnv(ndmGate, switchDur);

		gainEnv = fadeEnv * gateEnv;
		signalLocal = signalLocal * gainEnv;

		ndmLocal.conditionalPoll(fadeEnv, pollFreq, "[NdM] fadeEnv");
		ndmLocal.conditionalPoll(gateEnv, pollFreq, "[NdM] gateEnv");

		synthDebugId = DC.kr(synthDebugIdLocal);

		this.conditionalPoll(synthDebugId, pollFreq, "[NdM] synthId");
		this.conditionalPoll(fadeEnv, pollFreq, "[NdM] fadeEnv");

		if(fadeBusIndexLocal >= 0) {
			Out.kr(fadeBusIndexLocal, fadeEnv);
		};

		ndmLocal.checkRateMatch(busRate, signalRate);
		ndmLocal.writeSignalToBus(signalLocal, busIndex, busRate);

		^this;
	}

	prepareMakeProxyContext {
		var ctxLocal;
		var busesLocal;
		var argNamesLocal;
		var funcLocal;
		var outbusLocal;
		var argRatesLocal;
		var fadeBusIndexLocal;
		var synthDebugIdLocal;
		var switchDurLocal;

		ctxLocal = IdentityDictionary.new;

		busesLocal = if(this.argBuses.isKindOf(IdentityDictionary)) { this.argBuses } { IdentityDictionary.new };
		argNamesLocal = if(this.argNames.isNil) { [ ] } { if(this.argNames.isKindOf(Array)) { this.argNames } { [this.argNames] } };
		funcLocal = this.func;

		outbusLocal = outbus;

		argRatesLocal = if(this.argRates.isKindOf(IdentityDictionary)) { this.argRates } { IdentityDictionary.new };

		if(fadeBus.notNil) {
			fadeBusIndexLocal = fadeBus.index;
		} {
			fadeBusIndexLocal = -1;
		};

		synthDebugIdLocal = UniqueID.next;
		this.debugPost(
			"[NdM.makeProxyFunc] key: " ++ key
			++ "  synthDebugIdLocal: " ++ synthDebugIdLocal
		);

		switchDurLocal = switchDur;
		if(switchDurLocal.isNil) {
			switchDurLocal = 0.0;
		};

		ctxLocal.put(\busesLocal, busesLocal);
		ctxLocal.put(\argNamesLocal, argNamesLocal);
		ctxLocal.put(\funcLocal, funcLocal);
		ctxLocal.put(\outbusLocal, outbusLocal);
		ctxLocal.put(\argRatesLocal, argRatesLocal);
		ctxLocal.put(\fadeBusIndexLocal, fadeBusIndexLocal);
		ctxLocal.put(\synthDebugIdLocal, synthDebugIdLocal);
		ctxLocal.put(\switchDurLocal, switchDurLocal);
		ctxLocal.put(\ndmLocal, this);

		^ctxLocal;
	}

	// Debug helper: print argument metadata snapshot used by makeProxyFunc.
	postReadArgMeta { |busesLocal, argNamesLocal, argRatesLocal|
		this.debugPost("[DBG][READARG] busesLocal.class=" ++ busesLocal.class.asString);
		this.debugPost("[DBG][READARG] argNamesLocal.class=" ++ argNamesLocal.class.asString);
		this.debugPost("[DBG][READARG] argRatesLocal.class=" ++ argRatesLocal.class.asString);
		this.debugPost("[DBG][READARG] busesLocal=" ++ busesLocal.asString);
		this.debugPost("[DBG][READARG] argNamesLocal=" ++ argNamesLocal.asString);
		this.debugPost("[DBG][READARG] argRatesLocal=" ++ argRatesLocal.asString);

		^this;
	}

	// Debug helper: minimal diagnostics around valueArray inputs.
	postMakeProxyDiag { |funcLocal, argValues|
		this.debugPost("[DBG][MP] funcLocal.class=" ++ funcLocal.class.asString);
		this.debugPost("[DBG][MP] funcLocal.argNames=" ++ funcLocal.def.argNames.asString);

		this.debugPost("[DBG][MP] argValues.class=" ++ argValues.class.asString);
		this.debugPost("[DBG][MP] argValues.size=" ++ argValues.size.asString);
		this.debugPost("[DBG][MP] argValues elem classes="
			++ (argValues.collect { |val| val.class.asString }).asString
		);

		if(argValues.size > 0) {
			this.debugPost("[DBG][MP] argValues[0].class=" ++ argValues[0].class.asString);
		};

		this.debugPost("[DBG][MP] about to valueArray");

		^this;
	}

	// Validate signal return value for SynthDef graphs.
	// - Warn if signal itself is a Bus (language object)
	// - Throw if signal is a Collection containing any Bus
	validateSignalReturn { |signal|
		var hasBus;

		if(signal.isKindOf(Bus)) {
			("[NdMWarn] WARN: [NdM] signal function returned a Bus. key=" ++ key
				++ "  This may cause a DoesNotUnderstand error later (e.g. Bus * UGen). "
				++ "Use In.ar(busIndex) / InFeedback.ar(busIndex) to read from a bus."
			).postln;
		};

		hasBus = false;
		if(signal.isKindOf(Collection)) {
			hasBus = signal.any { |val| val.isKindOf(Bus) };
		};

		if(hasBus) {
			NdMError.new(
				"[NdM] invalid signal return (contains Bus). key=" ++ key
				++ "  hint: use In.ar(busIndex) / InFeedback.ar(busIndex) to read from a bus."
			).throw;
		};

		^this;
	}

	// Read all argument buses into UGen values.
	//
	// SPEC (argument-to-UGen safety):
	// - This method must return only UGen-ready values (In/InFeedback or constants).
	// - Language objects (e.g. NdM) must never reach UGen inputs; otherwise SynthDef build fails
	//   (example: "SinOsc arg: 'freq' has bad input: a NdM").
	// - If an argument bus exists but no upstream signal is connected yet, the resulting value can be 0,
	//   which yields silence until a source is wired. This is expected behavior.
	readArgValuesFromBuses { |busesLocal, argNamesLocal, argRatesLocal|
		var argValues;
		var idx;
		var argName;
		var busLocal;
		var rateLocal;
		var chansLocal;
		var valLocal;

		argValues = Array.newClear(argNamesLocal.size);

		idx = 0;
		while { idx < argNamesLocal.size } {
			argName = argNamesLocal[idx];

			busLocal = busesLocal[argName];
			rateLocal = argRatesLocal[argName];

			// Harden type (prevent non-Bus objects like NdM from entering UGen inputs)
			if(busLocal.notNil) {
				if(busLocal.isKindOf(Bus).not) {
					busLocal = nil;
				};
			};

			chansLocal = 1;
			if(busLocal.notNil) {
				chansLocal = busLocal.numChannels;
			};

			valLocal = 0;
			if(busLocal.notNil) {

				this.debugPost(
					"[DBG][ARGBUS][R] key=" ++ key.asString
					++ " arg=" ++ argName.asString
					++ " busIndex=" ++ busLocal.index.asString
					++ " busRate=" ++ busLocal.rate.asString
					++ " rateLocal=" ++ rateLocal.asString
					++ " chans=" ++ chansLocal.asString
				);

				if((rateLocal == \audio) || (busLocal.rate == \audio)) {
					valLocal = InFeedback.ar(busLocal.index, chansLocal);
				} {
					valLocal = In.kr(busLocal.index, chansLocal);
				};
			};

			argValues[idx] = valLocal;

			idx = idx + 1;
		};

		^argValues;
	}

	// Ensure this instance is registered in NdMNameSpace, even after reset.
	// Primary trigger: out()/out_()
	// Safety trigger: play()
	//
	// Responsibilities:
	// 1) Re-register nodes (key -> live NdM instance).
	// 2) Restore sink/edge state from the given outbus (when graph API exists).

	// ** ensureRegistered is defined in NdM-core (primary trigger policy). **

	// ensureRegistered { |outbusIn|
	// 	var monitor1;
	//
	// 	monitor1 = NdMNameSpace.acquire;
	//
	// 	// Prefer graph restore API when present.
	// 	if(monitor1.respondsTo(\restoreFromNode)) {
	// 		monitor1.restoreFromNode(key, this, outbusIn);
	// 		^this;
	// 	};
	//
	// 	// Fallback: minimal registry only (older versions).
	// 	monitor1.register(key, this);
	//
	// 	^this;
	// }

	// Graph restore helper (used after stop cleanup).
	// If outbus is a Bus (early-bus reference), restore edge key -> outbus.index.
	// If outbus is Integer/Array (direct sink), restore sink mark only.
	restoreGraphFromOutbus {
		var monitor1;
		var outbusLocal;

		// SPEC:
		// - Rebuild graph marking after stop/play based on current outbus.
		// - Mark sink when outbus is nil (default) or raw Integer/Array; do not mark for Bus.

		monitor1 = NdMNameSpace.instance;
		if(monitor1.isNil) {
			^this;
		};

		// Prefer new graph API when present.
		if(monitor1.respondsTo(\markSink).not) {
			^this;
		};

		outbusLocal = outbus;

		// Fix vX.Y.Z: B/C boundary guard (restore must not treat NdMSpace / invalid types as outbus)
		if(outbusLocal.isKindOf(NdMSpace)) {
			NdMError.reportOutBus(
				\outbusB,
				"NdM.restoreGraphFromOutbus@B-guardType",
				key,
				\B,
				"outbus",
				outbusLocal,
				"fallback0"
			);
			outbusLocal = 0;
		};

		if(
			outbusLocal.notNil
			&& (outbusLocal.isKindOf(Bus).not)
			&& (outbusLocal.isKindOf(Integer).not)
			&& (outbusLocal.isKindOf(Array).not)
		) {
			NdMError.reportOutBus(
				\outbusC,
				"NdM.restoreGraphFromOutbus@C-guardType",
				key,
				\C,
				"outbus",
				outbusLocal,
				"fallback0"
			);
			outbusLocal = 0;
		};

		this.debugPost(
			"[NdM.restoreGraphFromOutbus] stage=B src=outbus key: " ++ key.asString
			++ "  outbus: " ++ outbusLocal.asString
			++ "  class: " ++ outbusLocal.class.asString
		);

		if(outbusLocal.isKindOf(Bus)) {
			if(monitor1.respondsTo(\registerEdge)) {
				monitor1.registerEdge(key, outbusLocal.index);
			} {
				// Fallback: sink-only.
				monitor1.markSink(key);
			};
		} {
			if(outbusLocal.isKindOf(Integer) || outbusLocal.isKindOf(Array)) {
				monitor1.markSink(key);
			};
		};

		^this;
	}

	sanitizeFadeSwitch { |fadeIn, switchIn|
		var fadeDur;
		var fadeLocal;
		var switchLocal;

		fadeDur = fadeIn;
		if(fadeDur.isNil) {
			fadeDur = 0.0;
		};
		if(fadeDur < 0.0) {
			fadeDur = 0.0;
		};
		fadeLocal = fadeDur;

		switchLocal = switchIn;
		if(switchLocal.isNil) {
			switchLocal = 0.0;
		};
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		^[fadeLocal, switchLocal];
	}

	// Request a graph-order rebuild without changing the signal function.
	// B1: fade down (VarLag) -> swap source -> ampUp(init).
	requestGraphRebuild {
		var fadeDur;
		var fadeLocal;
		var switchLocal;
		var dbgFlag;

		var spaceLocal;
		var traceId;

		spaceLocal = NdMSpace.current;
		traceId = nil;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\ndm_requestGraphRebuild, key, -1, \none, -1, \none);
			spaceLocal.tracePush(
				'ndm.requestGraphRebuild.enter',
				key,
				\ndm_requestGraphRebuild,
				-1,
				("running:" ++ running.asString ++ " reqGenBefore:" ++ reqGen.asString).asSymbol,
				-1,
				\none,
				traceId
			);
			spaceLocal.traceEnd(traceId, \ndm_requestGraphRebuild, key, -1, \none, -1, \none);
		};

		dbgFlag = canDebug ? false;

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

		this.tracePush(
			'reqRebuild.before',
			key,
			\fadePlay,
			reqGen,
			("dbg:" ++ dbgFlag.asString
				++ " fade:" ++ fadeLocal.asString
				++ " switch:" ++ switchLocal.asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

		wantRebuild = true;
		wantFade = fadeLocal;
		wantSwitch = switchLocal;

		("[REQ] rebuild before=" ++ reqGen.asString).postln;
		reqGen = reqGen + 1;
		("[REQ] rebuild after=" ++ reqGen.asString).postln;

		("[TRACE][NdM][REQ-REBUILD] key=" ++ key.asString
			++ " reqGen(after)=" ++ reqGen.asString
		).postln;

		this.applyKick;

		^this;
	}

	// Start playback with VarLag-based fade-in and optional gate switch fade.
	play {
		var fadeDur;
		var fadeLocal;
		var switchLocal;
		var spaceLocal;
		var traceId;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\none,
				key,
				(reqGen ? 0),
				("proxyNil:" ++ proxy.isNil.asString
					++ " reqGenBefore:" ++ (reqGen ? 0).asString
				).asSymbol,
				-1,
				\none
			);
			spaceLocal.tracePush(
				'path.play.enter',
				key,
				\none,
				(reqGen ? 0),
				("proxyNil:" ++ proxy.isNil.asString
					++ " reqGenBefore:" ++ (reqGen ? 0).asString
				).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		if(proxy.isNil) {
			// Recreate NodeProxy so that a previously-freed NdM can be played again.
			proxy = Ndef(key);
			this.debugPost("[NdM.play] proxy recreated (was nil) key=" ++ key.asString);
		};

		// NOTE: Graph rebuild/restore trigger is unified to out()/out_().
		// play() must not touch graph bookkeeping.

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

		("[REQ] play before=" ++ reqGen.asString).postln;
		reqGen = reqGen + 1;
		("[REQ] play after=" ++ reqGen.asString).postln;

		this.applyKick;

		^this;
	}

	// Stop playback by fading amplitude to zero using VarLag.
	stop {
		var fadeDur;
		var fadeLocal;
		var switchLocal;
		var monitor1;
		var spaceLocal;
		var traceId;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\none,
				key,
				(reqGen ? 0),
				("proxyNil:" ++ proxy.isNil.asString
					++ " reqGenBefore:" ++ (reqGen ? 0).asString
				).asSymbol,
				-1,
				\none
			);
			spaceLocal.tracePush(
				'path.stop.enter',
				key,
				\none,
				(reqGen ? 0),
				("proxyNil:" ++ proxy.isNil.asString
					++ " reqGenBefore:" ++ (reqGen ? 0).asString
				).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		if(proxy.isNil) {
			this.debugPost("[NdM.stop] ignored: proxy is nil (already freed?) key=" ++ key.asString);
			^this;
		};

		monitor1 = NdMNameSpace.instance;
		if(monitor1.notNil) {
			if(monitor1.respondsTo(\removeGraphKey)) {
				monitor1.removeGraphKey(key);
			};
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

		("[REQ] stop before=" ++ reqGen.asString).postln;
		reqGen = reqGen + 1;
		("[REQ] stop after=" ++ reqGen.asString).postln;

		this.applyKick;

		^this;
	}

	// Request a graph-order rebuild without changing the signal function.
	// Used by NdMSpace.rebuildGraphIfDirty to ensure writer->reader node ordering.
	// B1: use VarLag fade (wantFade) so rebuild is click-free.
	// requestGraphRebuild {
	// 	var fadeLocal;
	// 	var switchLocal;
	//
	// 	fadeLocal = fadetime;
	// 	if(fadeLocal.isNil) {
	// 		fadeLocal = 0.0;
	// 	};
	// 	if(fadeLocal < 0.0) {
	// 		fadeLocal = 0.0;
	// 	};
	//
	// 	switchLocal = switchDur;
	// 	if(switchLocal.isNil) {
	// 		switchLocal = 0.0;
	// 	};
	// 	if(switchLocal < 0.0) {
	// 		switchLocal = 0.0;
	// 	};
	//
	// 	wantRebuild = true;
	// 	wantFade = fadeLocal;
	// 	wantSwitch = switchLocal;
	//
	// 	reqGen = reqGen + 1;
	// 	this.applyKick;
	//
	// 	^this;
	// }

	applyKick {
		var doStart;
		var proxyLocal;
		var isPlayingLocal;
		var nodeIdLocal;
		var dbgFlag;
		var spaceLocal;
		var traceId;

		dbgFlag = canDebug ? false;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\none, key, reqGen, ("running:" ++ running.asString).asSymbol, -1, \none);
			spaceLocal.tracePush('applyKick.enter', key, \none, reqGen, ("running:" ++ running.asString).asSymbol, -1, \none, traceId);
		};

		doStart = running.not;

		if(doStart) {
			if(spaceLocal.notNil) {
				spaceLocal.tracePush(
					'state.applyKick.start.1',
					key,
					\none,
					reqGen,
					("running:" ++ running.asString
						++ " -> true"
						++ " reason:applyKick.start.1"
					).asSymbol,
					-1,
					\none,
					traceId
				);
			};
			running = true;
		};

		this.debugPost(
			"[APPLY-KICK] key=" ++ key.asString
			++ " running=" ++ running.asString
			++ " reqGen=" ++ reqGen.asString
			++ " doStart=" ++ doStart.asString
		);

		proxyLocal = proxy;
		isPlayingLocal = false;
		nodeIdLocal = nil;
		if(proxyLocal.notNil) {
			isPlayingLocal = proxyLocal.isPlaying;
			try { nodeIdLocal = proxyLocal.nodeID; } { nodeIdLocal = nil; };
		};

		if(spaceLocal.notNil) {
			spaceLocal.tracePush(
				'proxy.applyKick.read',
				key,
				\none,
				reqGen,
				("running:" ++ running.asString
					++ " proxyNil:" ++ proxyLocal.isNil.asString
					++ " isPlaying:" ++ isPlayingLocal.asString
					++ " nodeID:" ++ nodeIdLocal.asString
					++ " reason:applyKick.read"
				).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		this.debugPost(
			"[APPLY-KICK] proxyState key=" ++ key.asString
			++ " running=" ++ running.asString
			++ " isPlaying=" ++ isPlayingLocal.asString
			++ " nodeID=" ++ nodeIdLocal.asString
		);

		if(doStart) {
			if(spaceLocal.notNil) {
				spaceLocal.tracePush(
					'state.applyKick.start.2',
					key,
					\none,
					reqGen,
					("running:" ++ running.asString
						++ " -> true"
						++ " reason:applyKick.start.2"
					).asSymbol,
					-1,
					\none,
					traceId
				);
			};
			running = true;

			if(spaceLocal.notNil) {
				spaceLocal.tracePush('applyKick.startFork', key, \none, reqGen, ("running:" ++ running.asString).asSymbol, -1, \none, traceId);
			};

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
					if(spaceLocal.notNil) {
						spaceLocal.tracePush(
							'state.applyKick.cleanup.protect',
							key,
							\none,
							reqGen,
							("running:" ++ running.asString
								++ " -> false"
								++ " reason:applyKick.cleanup.protect"
							).asSymbol,
							-1,
							\none,
							traceId
						);
					};
					running = false;

					// Safe to clear even if not set; prevents leakage across failures.
					NdM.clearBuildingKey;
				};
			};
		} {
			if(spaceLocal.notNil) {
				spaceLocal.tracePush('applyKick.alreadyRunning', key, \none, reqGen, ("running:" ++ running.asString).asSymbol, -1, \none, traceId);
			};

			this.debugPost(
				"[APPLY] already running key=" ++ key.asString
				++ " running=" ++ running.asString
				++ " reqGen=" ++ reqGen.asString
			);
		};

		if(spaceLocal.notNil) {
			spaceLocal.traceEnd(traceId, \none, key, reqGen, \none, -1, \none);
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
		var wantRebuildLocal;
		var funcLocal;
		var funcChanged;
		var traceId;
		var spaceLocal;

		xr = IdentityDictionary.new;

		proxyLocal = proxy;

		spaceLocal = NdMSpace.current;
		traceId = nil;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\fadePlay,
				key,
				(reqGen ? 0),
				\applySnapshot,
				-1,
				\none
			);

			spaceLocal.tracePush(
				'path.applySnapshot.enter',
				key,
				\fadePlay,
				(reqGen ? 0),
				("snapId:" ++ xr.identityHash.asString).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		wantPlayLocal = if(wantPlay.isNil) { false } { wantPlay };
		wantFreeLocal = if(wantFree.isNil) { false } { wantFree };
		wantRebuildLocal = if(wantRebuild.isNil) { false } { wantRebuild };

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
		xr.put(\wantRebuild, wantRebuildLocal);
		xr.put(\fade, fadeLocal);
		xr.put(\switchDur, switchLocal);
		xr.put(\func, funcLocal);
		xr.put(\funcChanged, funcChanged);

		^xr;
	}

	kickSnapshot {
		var xr;
		var proxyLocal;
		var runningLocal;
		var reqGenLocal;
		var wantPlayLocal;
		var isPlayingLocal;
		var nodeIdLocal;
		var traceId;
		var spaceLocal = NdMSpace.current;

		xr = IdentityDictionary.new;

		proxyLocal = proxy;

		runningLocal = if(running.isNil) { false } { running };
		reqGenLocal = (reqGen ? 0);
		wantPlayLocal = if(wantPlay.isNil) { false } { wantPlay };

		traceId = spaceLocal.traceBegin(
			\kickSnapshot,
			key,
			reqGenLocal,
			\fadePlay,
			-1,
			\none
		);

		spaceLocal.tracePush(
			'path.kickSnapshot.enter',
			key,
			\fadePlay,
			reqGenLocal,
			("snapId:" ++ xr.identityHash.asString
				++ " running:" ++ runningLocal.asString
				++ " wantPlay:" ++ wantPlayLocal.asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

		isPlayingLocal = false;
		nodeIdLocal = nil;

		if(proxyLocal.notNil) {
			if(proxyLocal.respondsTo(\isPlaying)) {
				isPlayingLocal = proxyLocal.isPlaying;
			};

			if(proxyLocal.respondsTo(\nodeID)) {
				try {
					nodeIdLocal = proxyLocal.nodeID;
				} {
					nodeIdLocal = nil;
				};
			};
		};

		spaceLocal.tracePush(
			'proxy.kickSnapshot.read',
			key,
			\fadePlay,
			reqGenLocal,
			("snapId:" ++ xr.identityHash.asString
				++ " proxyNil:" ++ proxyLocal.isNil.asString
				++ " isPlaying:" ++ isPlayingLocal.asString
				++ " nodeID:" ++ nodeIdLocal.asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

		xr.put(\running, runningLocal);
		xr.put(\reqGen, reqGenLocal);
		xr.put(\wantPlay, wantPlayLocal);
		xr.put(\isPlaying, isPlayingLocal);
		xr.put(\nodeID, nodeIdLocal);

		("[KICKSNAP] key=" ++ key.asString
			++ " snapId=" ++ xr.identityHash.asString
			++ " snap.reqGen=" ++ reqGenLocal.asString
			++ " snap.wantPlay=" ++ wantPlayLocal.asString
			++ " snap.running=" ++ runningLocal.asString
			++ " isPlaying=" ++ isPlayingLocal.asString
			++ " nodeID=" ++ nodeIdLocal.asString
		).postln;

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
		var wantRebuildLocal;
		var funcLocal;
		var funcChanged;
		var ampInit;
		// var proxyFunc;
		// var monitor1;
		var snap;

		var spaceLocal;
		var traceId;

		// preemptable-wait temps (must be declared at block head)
		// removed: remain/step/remain2/step2 (handled by applyWaitGen)

		// formerly mid-block var in old apply
		// var srv;

		spaceLocal = NdMSpace.current;
		traceId = nil;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\none,
				key,
				(reqGen ? 0),
				("reqGenStart:" ++ (reqGen ? 0).asString).asSymbol,
				-1,
				\none
			);
			spaceLocal.tracePush(
				'path.apply.enter',
				key,
				\none,
				(reqGen ? 0),
				("reqGenStart:" ++ (reqGen ? 0).asString).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		doLoop = true;

		while { doLoop } {

			genStart = reqGen;
			("[APPLY] start genStart=" ++ genStart.asString ++ " reqGen=" ++ reqGen.asString).postln;

			proxyLocal = proxy;
			if(proxyLocal.isNil) {
				this.debugPost("[APPLY] proxy is nil");
				if(spaceLocal.notNil) {
					spaceLocal.tracePush(
						'state.apply.abort.proxyNil.outer',
						key,
						\none,
						genStart,
						("running:" ++ running.asString ++ " -> false reason:apply.abort.proxyNil.outer").asSymbol,
						-1,
						\none,
						traceId
					);
				};
				running = false;
				doLoop = false;
			} {
				snap = this.applySnapshot;

				proxyLocal = snap.at(\proxy);
				if(proxyLocal.isNil) {
					this.debugPost("[APPLY] proxy is nil");
					if(spaceLocal.notNil) {
						spaceLocal.tracePush(
							'state.apply.abort.proxyNil.inner',
							key,
							\none,
							genStart,
							("running:" ++ running.asString ++ " -> false reason:apply.abort.proxyNil.inner").asSymbol,
							-1,
							\none,
							traceId
						);
					};
					running = false;
					doLoop = false;
				} {
					wantPlayLocal = snap.at(\wantPlay);

					wantFreeLocal = snap.at(\wantFree);
					wantRebuildLocal = snap.at(\wantRebuild);
					fadeLocal = snap.at(\fade);
					switchLocal = snap.at(\switchDur);
					funcLocal = snap.at(\func);
					funcChanged = snap.at(\funcChanged);

					ampInit = funcChanged;

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
					// B0) proxy-side rebuild (fadeDown -> swap source -> ampUp(init))
					if(wantRebuildLocal) {
						this.applyHandleGraphRebuild(genStart, proxyLocal, fadeLocal, switchLocal, funcLocal);
						ampInit = true;
					};

					// B) function switch (gate down -> swap -> gate up)
					if(funcChanged) {
						this.applyHandleFuncSwitch(genStart, proxyLocal, fadeLocal, switchLocal, funcLocal);
					};

					// C) play/stop
					this.applyHandlePlayStop(genStart, proxyLocal, fadeLocal, switchLocal, wantPlayLocal, ampInit);

					// generation gate
					if(reqGen != genStart) {
						("[APPLY] end genStart=" ++ genStart.asString
							++ " reqGen=" ++ reqGen.asString
							++ " result=rerun"
						).postln;
						// rerun latest
					} {
						("[APPLY] end genStart=" ++ genStart.asString
							++ " reqGen=" ++ reqGen.asString
							++ " result=done"
						).postln;
						doLoop = false;
					};

					if(doLoop.not) {
						if(spaceLocal.notNil) {
							spaceLocal.tracePush(
								'state.apply.done',
								key,
								\none,
								genStart,
								("running:" ++ running.asString ++ " -> false reason:apply.done").asSymbol,
								-1,
								\none,
								traceId
							);
						};
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
		var traceId;
		var spaceLocal = NdMSpace.current;

		traceId = spaceLocal.traceBegin(
			\applyWaitGen,
			key,
			genStart,
			\fadePlay,
			-1,
			\none
		);

		spaceLocal.tracePush(
			'path.applyWaitGen.enter',
			key,
			\fadePlay,
			genStart,
			("dur:" ++ (dur ? 0.0).asString
				++ " step:" ++ (step ? 0.05).asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

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
		var traceId;
		var spaceLocal = NdMSpace.current;

		traceId = spaceLocal.traceBegin(
			\applyHandleFree,
			key,
			genStart,
			\fadePlay,
			-1,
			\none
		);

		spaceLocal.tracePush(
			'path.applyHandleFree.enter',
			key,
			\fadePlay,
			genStart,
			("fade:" ++ fadeLocal.asString
				++ " switch:" ++ switchLocal.asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

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
				if(monitor1.respondsTo(\removeGraphKey)) {
					monitor1.removeGraphKey(key);
				};
				monitor1.unregister(key, this);
			};

			if(key.notNil) {
				instances.removeAt(key);
			};

			wantFree = false;
			wantPlay = false;
			appliedFunc = nil;

			spaceLocal.tracePush(
				'state.applyFree.done',
				key,
				\fadePlay,
				genStart,
				("running:" ++ running.asString
					++ " -> false"
					++ " reason:apply.free.done"
				).asSymbol,
				-1,
				\none,
				traceId
			);
			running = false;
			this.debugPost("[APPLY] freed gen=" ++ genStart.asString);
		} {
			this.debugPost("[APPLY] abort free gen=" ++ genStart.asString
				++ " current=" ++ reqGen.asString);
		};

		^this;
	}

	applyHandleGraphRebuild { |genStart, proxyLocal, fadeLocal, switchLocal, funcLocal|
		var proxyFunc;
		var keyLocal;
		var nodeBefore;
		var nodeAfter;
		var grpBefore;
		var grpAfter;
		var grpIdBefore;
		var grpIdAfter;
		var traceId;
		var spaceLocal = NdMSpace.current;

		traceId = spaceLocal.traceBegin(
			\applyHandleGraphRebuild,
			key,
			genStart,
			\fadePlay,
			-1,
			\none
		);

		this.tracePush(
			'path.applyHandleGraphRebuild.enter',
			key,
			\fadePlay,
			genStart,
			("fade:" ++ fadeLocal.asString
				++ " switch:" ++ switchLocal.asString
				++ " proxyIsPlaying:" ++ proxyLocal.isPlaying.asString
			).asSymbol,
			-1,
			\none,
			traceId
		);

		// Fade down to 0 with VarLag, then rebuild source to enforce node ordering.
		if(reqGen == genStart) {
			proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);
			proxyLocal.set(\ndmAmp, 0.0);
		};

		// Preemptable wait during fadeDown.
		// - Skip wait when proxy is not playing (initial play).
		// - Use NdM.fadeGap (click-safe gap), not user fade time.
		if(proxyLocal.isPlaying) {
			this.applyWaitGen(
				genStart,
				NdM.fadeGap ? 0.0,
				0.05
			);
		};

		if(reqGen == genStart) {
			proxyFunc = this.makeProxyFunc;

			keyLocal = key;

			nodeBefore = nil;
			nodeAfter = nil;
			grpBefore = nil;
			grpAfter = nil;
			grpIdBefore = nil;
			grpIdAfter = nil;

			try { nodeBefore = proxyLocal.nodeID; } { nodeBefore = nil; };
			try { grpBefore = proxyLocal.group; } { grpBefore = nil; };
			if(grpBefore.notNil) {
				try { grpIdBefore = grpBefore.nodeID; } { grpIdBefore = nil; };
			};

			this.debugPost(
				"[DBG][GRAPH][REBUILD] before swap key=" ++ keyLocal.asString
				++ " gen=" ++ genStart.asString
				++ " nodeID=" ++ nodeBefore.asString
				++ " groupID=" ++ grpIdBefore.asString
			);

			if(grpBefore.notNil) {
				this.debugPost(
					"[DBG][GRAPH][REBUILD] queryTree(before) key=" ++ keyLocal.asString
					++ " groupID=" ++ grpIdBefore.asString
				);
				grpBefore.queryTree(true);
			};

			// Keep amp at 0 before swapping.
			proxyLocal.set(
				\ndmFade, fadeLocal,
				\switchDur, switchLocal,
				\ndmGate, 1,
				\ndmAmp, 0.0
			);

			proxyLocal.source = proxyFunc;
			appliedFunc = funcLocal;

			try { nodeAfter = proxyLocal.nodeID; } { nodeAfter = nil; };
			try { grpAfter = proxyLocal.group; } { grpAfter = nil; };
			if(grpAfter.notNil) {
				try { grpIdAfter = grpAfter.nodeID; } { grpIdAfter = nil; };
			};

			this.debugPost(
				"[DBG][GRAPH][REBUILD] after  swap key=" ++ keyLocal.asString
				++ " gen=" ++ genStart.asString
				++ " nodeID=" ++ nodeAfter.asString
				++ " groupID=" ++ grpIdAfter.asString
			);

			if(grpAfter.notNil) {
				this.debugPost(
					"[DBG][GRAPH][REBUILD] queryTree(after)  key=" ++ keyLocal.asString
					++ " groupID=" ++ grpIdAfter.asString
				);
				grpAfter.queryTree(true);
			};

			wantRebuild = false;

		} {
			this.debugPost("[APPLY] abort graphRebuild gen=" ++ genStart.asString
				++ " current=" ++ reqGen.asString);
		};

		^this;
	}

	applyHandleFuncSwitch { |genStart, proxyLocal, fadeLocal, switchLocal, funcLocal|
		var proxyFunc;
		var traceId;
		var spaceLocal = NdMSpace.current;

		traceId = nil;
		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\applyHandleFuncSwitch,
				key,
				genStart,
				\fadePlay,
				-1,
				\none
			);

			spaceLocal.tracePush(
				'path.applyHandleFuncSwitch.enter',
				key,
				\fadePlay,
				genStart,
				("fade:" ++ fadeLocal.asString
					++ " switch:" ++ switchLocal.asString
					++ " proxyIsPlaying:" ++ proxyLocal.isPlaying.asString
				).asSymbol,
				-1,
				\none,
				traceId
			);
		};

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

	applyHandlePlayStop { |genStart, proxyLocal, fadeLocal, switchLocal, wantPlayLocal, ampInit|
		var srvLocal;
		var doPlay;
		var isPlayingBefore;
		var nodeIdBefore;
		var isPlayingAfter;
		var nodeIdAfter;
		var traceId;
		var spaceLocal = NdMSpace.current;

		traceId = nil;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(
				\applyHandlePlayStop,
				key,
				genStart,
				\fadePlay,
				-1,
				\none
			);

			spaceLocal.tracePush(
				'path.applyHandlePlayStop.enter',
				key,
				\fadePlay,
				genStart,
				("wantPlay:" ++ wantPlayLocal.asString
					++ " ampInit:" ++ ampInit.asString
					++ " fade:" ++ fadeLocal.asString
					++ " switch:" ++ switchLocal.asString
				).asSymbol,
				-1,
				\none,
				traceId
			);
		};

		// Normalize play decision (avoid unintended stop path)
		doPlay = wantPlayLocal && wantFree.not;

		if(reqGen == genStart) {

			if(doPlay) {
				isPlayingBefore = proxyLocal.isPlaying;
				nodeIdBefore = nil;
				try { nodeIdBefore = proxyLocal.nodeID; } { nodeIdBefore = nil; };
				this.debugPost(
					"[APPLY][PLAY] before play gen=" ++ genStart.asString
					++ " key=" ++ key.asString
					++ " wantPlay=" ++ wantPlayLocal.asString
					++ " wantFree=" ++ wantFree.asString
					++ " isPlaying=" ++ isPlayingBefore.asString
					++ " nodeID=" ++ nodeIdBefore.asString
				);

				proxyLocal.play;

				isPlayingAfter = proxyLocal.isPlaying;
				nodeIdAfter = nil;
				try { nodeIdAfter = proxyLocal.nodeID; } { nodeIdAfter = nil; };
				this.debugPost(
					"[APPLY][PLAY] after  play gen=" ++ genStart.asString
					++ " key=" ++ key.asString
					++ " isPlaying=" ++ isPlayingAfter.asString
					++ " nodeID=" ++ nodeIdAfter.asString
				);

				// Always push fade first (VarLag timebase).
				proxyLocal.set(\ndmFade, fadeLocal, \switchDur, switchLocal, \ndmGate, 1);

				// Critical: when ampInit, force amp init to 0 then ramp to 1.
				if(ampInit && (fadeLocal > 0.0)) {

					proxyLocal.set(\ndmAmp, 0.0);

					// Sync insurance (fallback to Server.default)
					srvLocal = this.getServer;
					if(srvLocal.isNil) {
						srvLocal = Server.default;
					};
					if(srvLocal.notNil) {
						try {
							srvLocal.sync;
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
