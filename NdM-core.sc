// Debug flags:
//
//   - dbg / dbg_ : instance-only flag that controls debugPost() output.
//                  When true, NdM methods print additional status logs.
//   - poll / poll_: instance-only flag that controls conditionalPoll() usage.
//                   When true, fade and gate UGens may insert poll() for
//                   inspection.
//
// These flags are available only on NdM instances. NdMSpaceSpec does not
// implement dbg or poll, and no doesNotUnderstand-based message routing is used.

NdM : Object {

	// === Global registry of NdM instances, and class-level fade gap parameter. ===

	// Global table storing all NdM instances by key (Symbol → NdM).
	classvar <instances;

	// Global fade gap used when performing VarLag-based fade-in transitions.
	classvar <>fadeGap;

	classvar <buildingKey;


	// === Core instance state and argument/bus management fields. ===

	// Symbol name of this NdM instance (also used as Ndef key).
	var <key;

	// NodeProxy (Ndef) object associated with this NdM, created at init.
	var <proxy;

	// User-supplied function defining the UGen graph.
	var <func;

	// Mapping: argName → Bus used to feed each UGen argument.
	var <argBuses;

	// Ordered list of argument names (Symbol) as parsed from the function.
	var <argNames;

	// Mapping: argName → rate (\audio / \control) resolved at initialization.
	var <argRates;

	// Output bus specification provided by user (nil / Bus / Int / Array).
	var outbus;

	// Default argument rate derived from key suffix (“_a” or “_k”), if any.
	var <keyRate;


	// === Fade and function-switch parameters shared across modules. ===

	// Fade duration used for VarLag-based fade-in/out operations.
	var fadetime;

	// Optional Bus used to expose fade envelope externally (may remain nil).
	var fadeBus;

	// Crossfade duration used when switching functions (ndmGate based).
	var <>switchDur;

	// Tracks whether initial fade setup has been completed for this instance.
	var hasFadeInit;

	// --- state machine (single-writer) ---
	var wantPlay;
	var wantFree;
	var wantFunc;
	var wantRebuild;
	var wantFade;
	var wantSwitch;
	var reqGen;
	var running;
	var appliedFunc;

	// --- debug flags (instance-level) ---
	var <>canDebug;
	var <>canPoll;
	var <>pollFreq;

	// Initialize class registry at library compile time.
	*initClass {
		instances = IdentityDictionary.new;
	}

	// Lookup an NdM instance by key.
	*find { |key|
		^instances[key.asSymbol];
	}

	// Entry point: create or reuse an NdM, then set function/outbus.
	*new { |key, func, outbus, whereIn|
		var instance;
		var keySymbol;
		var result;
		var hasOld;
		var outLabel;
		var debugMessage;
		var whereLocal;
		// var monitor1;
		// var ownerInfo;

		whereLocal = whereIn ? \none;

		// Label outbus for log readability.
		if(outbus.isNil) {
			outLabel = "nil";
		} {
			outLabel = outbus.asString;
		};

		debugMessage =　("[NdM.new] request  key: " ++ key.asString
			++ "  outbus: " ++ outLabel ++ "\n"
		);

		keySymbol = key.asSymbol;
		hasOld = instances[keySymbol].notNil;

		debugMessage =　debugMessage ++　("[NdM.new] keySymbol: " ++ keySymbol.asString
			++ "  hasExisting: " ++ hasOld.asString ++ "\n"
		);

		instance = instances[keySymbol];
		if(instance.notNil) {
			// Existing instance is reused; only function/outbus update.
			// BUT: NdMNameSpace may have been reset, so we must re-register and restore sink/edge state.
			debugMessage =　debugMessage ++　"[NdM.new] reuse existing instance; re-register monitor; call setFunc\n";

			// Existing instance is reused; NdMNameSpace may have been reset.
			// Ensure the instance is re-registered and sink/edge is restored for this outbus.
			instance.ensureRegistered(outbus);

			instance.setFunc(func, outbus, whereLocal);
			result = instance;
			^result;
		};

		debugMessage =　debugMessage ++　"[NdM.new] create new instance";

		// == Log for development == //
		// debugMessage.postln;

		instance = super.new;
		instance.init(keySymbol, func, outbus, whereLocal);

		/** IMPORTANT: Regardless of whether there is a placeholder or not,
		'ensureRegistered' and 'setFunc' are always executed as long as NdM.new is passed. **/
		// Ensure the same side-effects as the reuse path.
		instance.ensureRegistered(outbus);
		instance.setFunc(func, outbus, whereLocal);

		// Register the newly created instance into the global table.
		instances[keySymbol] = instance;
		result = instance;
		^result;
	}

	// Free all NdM instances currently registered.
	*freeAll {
		instances.values.do { |instance|
			instance.free;
		};
	}

	// Stop (fade to silence) all NdM instances currently registered.
	*stopAll {
		instances.values.do { |instance|
			instance.stop;
		};
	}

	*setBuildingKey { |keySymbol|
		buildingKey = keySymbol;
		^this;
	}

	*clearBuildingKey {
		buildingKey = nil;
		^this;
	}

	*inferRateUnified { |keySymbol, argSymbol|
		var keyStr;
		var argStr;
		var rateSymbol;

		keyStr = keySymbol.asString;
		argStr = argSymbol.asString;

		// Priority: arg suffix > key suffix > default audio
		if(argStr.endsWith("_k")) {
			rateSymbol = \control;
		} {
			if(argStr.endsWith("_a")) {
				rateSymbol = \audio;
			} {
				if(keyStr.endsWith("_k")) {
					rateSymbol = \control;
				} {
					if(keyStr.endsWith("_a")) {
						rateSymbol = \audio;
					} {
						rateSymbol = \audio;
					};
				};
			};
		};

		^rateSymbol;
	}

	// Returns: [busObject, rateSymbol]
	*resolveArgBus { |keySymbol, argSymbol, serverIn, whereIn, traceIdIn|
		var monitor;
		var rateSymbol;
		var busIndex;
		var inferredRate;
		var serverLocal;
		var busObject;
		var result;

		// --- argbus trace locals ---
		var spaceLocal;
		var whereLocal;
		var auxState;
		var auxValue;
		var traceIdLocal;

		traceIdLocal = traceIdIn;

		serverLocal = serverIn;
		if(serverLocal.isNil) {
			serverLocal = Server.default;
		};

		monitor = NdMNameSpace.acquire;

		rateSymbol = monitor.recallRate(keySymbol, argSymbol);
		busIndex = monitor.recallBus(keySymbol, argSymbol);

		// ---- argbus.recall (read-side recall point) ----
		spaceLocal = NdMSpace.current;
		whereLocal = whereIn ? \none;
		auxState = ("arg:" ++ argSymbol.asString).asSymbol;
		auxValue = if(busIndex.isNil) { -1 } { busIndex };

		if(spaceLocal.notNil) {
			spaceLocal.tracePush(
				'argbus.recall',
				keySymbol,
				whereLocal,
				-1,
				auxState,
				auxValue,
				'reason:recall',
				traceIdLocal
			);
		};

		// 1) Existing record wins (both must exist)
		if(rateSymbol.notNil && busIndex.notNil) {
			busObject = Bus.new(rateSymbol, busIndex, 1, serverLocal);
			result = [busObject, rateSymbol];
			^result;
		};

		// 3) Inconsistent (only one exists) => error
		if(rateSymbol.notNil || busIndex.notNil) {
			NdMError(
				"[NdM] inconsistent arg bus record: key="
				++ keySymbol.asString
				++ " arg="
				++ argSymbol.asString
				++ " rate="
				++ rateSymbol.asString
				++ " busIndex="
				++ busIndex.asString
			).throw;
		};

		// 2) Infer and allocate, then remember BOTH (single path)
		inferredRate = this.inferRateUnified(keySymbol, argSymbol);

		if(inferredRate == \control) {
			busObject = Bus.control(serverLocal, 1);
		} {
			busObject = Bus.audio(serverLocal, 1);
		};

		// ---- argbus.alloc (read-side alloc point: busIndex is now fixed) ----
		spaceLocal = NdMSpace.current;
		whereLocal = whereIn ? \none;
		auxState = ("arg:" ++ argSymbol.asString).asSymbol;
		auxValue = if(busObject.isNil) { -1 } { busObject.index };

		if(spaceLocal.notNil) {
			spaceLocal.tracePush(
				'argbus.alloc',
				keySymbol,
				whereLocal,
				-1,
				auxState,
				auxValue,
				'reason:alloc',
				traceIdLocal
			);
		};

		monitor.rememberBus(keySymbol, argSymbol, busObject.index);
		monitor.rememberRate(keySymbol, argSymbol, inferredRate);

		// ---- argbus.remember (read-side remember complete point) ----
		spaceLocal = NdMSpace.current;
		whereLocal = whereIn ? \none;
		auxState = ("arg:" ++ argSymbol.asString).asSymbol;
		auxValue = if(busObject.isNil) { -1 } { busObject.index };

		if(spaceLocal.notNil) {
			spaceLocal.tracePush(
				'argbus.remember',
				keySymbol,
				whereLocal,
				-1,
				auxState,
				auxValue,
				'reason:remember',
				traceIdLocal
			);
		};

		result = [busObject, inferredRate];
		^result;
	}

	// Main initializer: setup key, function, argument metadata, and bus allocation.
	init { |keySymbol, funcIn, outbusIn, whereIn|
		var server;
		// var monitorLocal;
		var monitor1;
		var keyString;
		var parsedArgs;
		var rateLabel;
		var debugMessage;
		var whereLocal;

		whereLocal = whereIn ? \none;

		debugMessage =　("[NdM.init] keySymbol: " ++ keySymbol.asString
			++ "  outbusIn: " ++ outbusIn.asString
		);

		// == Log for development == //
		// debugMessage.postln;

		key = keySymbol;
		func = funcIn;

		// Policy: persist outbus as raw-only (or nil) inside NdM.
		// SPEC:
		// - The outbus stored in NdM is restricted to raw-only: nil / Integer / Array / Bus.
		// - The init argument outbusIn must be raw-only as well.
		// - Invalid types must be reported (NdMError.reportOutBus) and must fall back to 0.
		{
			var rawOut;

			rawOut = outbusIn;

			if(rawOut.notNil) {

				if(
					(rawOut.notNil)
					&& (rawOut.isKindOf(Bus).not)
					&& (rawOut.isKindOf(Integer).not)
					&& (rawOut.isKindOf(Array).not)
				) {
					NdMError.reportOutBus(
						\outbusB,
						"NdM.init@B-guardType",
						key,
						\B,
						"outbusIn",
						rawOut,
						"fallback0"
					);
					rawOut = 0;
				};
			};

			outbus = rawOut;
		}.value;

		fadetime = 0.01;					// Default fade time before user modification.
		fadeGap = NdM.fadeGap ?? 0.04;		// Delay between fade start and amp update.
		hasFadeInit = false;				// Track if initial fade setup has run.

		// Determine default arg-rate (key suffix).
		keyString = key.asString;
		keyRate = this.parseKeyRate(keyString);

		if(keyRate.notNil) {
			rateLabel = "audio";
			if(keyRate == \control) {
				rateLabel = "control";
			};
			(
				"[NdM] key: " ++ keyString
				++ "  defaultArgRate: " ++ rateLabel
				++ " (from key suffix)"
			).postln;
		};

		// NdMNameSpace is the authoritative registry for:
		// - key -> live NdM nodes (register)
		// - argName -> busIndex/rate history (remember/recall)
		// - graph bookkeeping (sinks/edges) when the new API exists
		monitor1 = NdMNameSpace.acquire;
		monitor1.register(key, this);

		// Graph hook (Task 1.5):
		// NdMSpace.put creates NdM(key, func, outbusIn) without calling NdM.out,
		// so we must record sink/edge here as well.
		// SPEC:
		// - Graph bookkeeping (sink/edge) must be recorded only for the raw output form: Integer / Array / Bus.
		{
			var rawOut;

			rawOut = outbus;

			if(monitor1.respondsTo(\markSink)) {
				if(rawOut.isNil) {
					// Default out is treated as sink (e.g. 0) even when outbus was not explicitly set yet.
					monitor1.markSink(key);
				} {
					if(rawOut.isKindOf(Bus)) {
						if(monitor1.respondsTo(\unmarkSink)) {
							monitor1.unmarkSink(key);
						};
						if(monitor1.respondsTo(\registerEdge)) {
							monitor1.registerEdge(key, rawOut.index);
						} {
							monitor1.markSink(key);
						};
					} {
						if(rawOut.isKindOf(Integer) || rawOut.isKindOf(Array)) {
							monitor1.markSink(key);
						} {
							if(monitor1.respondsTo(\unmarkSink)) {
								monitor1.unmarkSink(key);
							};
						};
					};
				};
			};
		}.value;

		server = Server.default;

		// Parse argument names and rates from the function definition.
		parsedArgs = this.parseArgList(func, keyRate);
		argNames = parsedArgs[0];
		argRates = parsedArgs[1];

		// Prepare argument-to-Bus mapping table.
		argBuses = IdentityDictionary.new;

		// Allocate or reuse buses for each argument name.
		argNames.do { |argName|
			var bus;
			var busIndex;
			var rate;

			rate = argRates[argName] ? \audio;

			// Attempt to reuse a previously stored bus index.
			busIndex = nil;
			// Prepare argument-to-Bus mapping table.
			argBuses = IdentityDictionary.new;

			// Allocate/reuse buses for each argument name (unified rule).
			argNames.do { |argName|
				# bus, rate = NdM.resolveArgBus(key, argName, server, whereLocal);

				argBuses[argName] = bus;
				argRates[argName] = rate;
			};
		};

		// Proxy is instantiated here, but source is set only at play-time.
		proxy = Ndef(key);

		// Initialize fade-related fields.
		fadeBus = nil;
		switchDur = 0.05;	// Default crossfade duration for function switching.
		pollFreq = 10;		// Default polling frequency

		// --- state machine init ---
		wantPlay = false;
		wantFree = false;
		wantFunc = func;
		wantFade = (fadetime ? 0.0);
		wantSwitch = (switchDur ? 0.0);
		reqGen = 0;
		running = false;
		appliedFunc = func;

		^this;
	}

	// Determine default arg-rate from key suffix (“_a” or “_k”).
	parseKeyRate { |keyString|
		var rate;

		rate = nil;

		if(keyString.endsWith("_a")) {
			rate = \audio;
		} {
			if(keyString.endsWith("_k")) {
				rate = \control;
			};
		};

		^rate;
	}

	// Extract argument suffix (“_a” or “_k”) and produce (symbolName, rate).
	parseArgNameRate { |rawName|
		var rate;
		var symbolName;
		var size;

		rate = \audio;
		symbolName = rawName.asSymbol;
		size = rawName.size;

		if(size > 2) {
			if(rawName.endsWith("_a")) {
				rate = \audio;
				symbolName = rawName.asSymbol;
				// symbolName = rawName.copyRange(0, size - 3).asSymbol;
			} {
				if(rawName.endsWith("_k")) {
					rate = \control;
					symbolName = rawName.asSymbol;
					// symbolName = rawName.copyRange(0, size - 3).asSymbol;
				};
			};
		};

		^[symbolName, rate];
	}

	// Analyze all function arguments and compute their symbol names and rates.
	parseArgList { |funcIn, keyRateIn|
		var argNamesLocal;
		var argRatesLocal;
		var argList;
		var parsed;
		var symbolName;
		var rate;
		var hasSuffix;
		var rawString;

		argNamesLocal = Array.new;
		argRatesLocal = IdentityDictionary.new;

		funcIn.def.argNames.do { |rawName|
			hasSuffix = false;
			rawString = rawName.asString;

			if(rawString.endsWith("_a")) {
				hasSuffix = true;
			};
			if(rawString.endsWith("_k")) {
				hasSuffix = true;
			};

			parsed = this.parseArgNameRate(rawString);
			symbolName = parsed[0];
			rate = parsed[1];

			// If key defines a rate, it overrides unsuffixed arguments.
			if(keyRateIn.notNil && (hasSuffix.not)) {
				rate = keyRateIn;
			};

			argNamesLocal = argNamesLocal.add(symbolName);
			argRatesLocal[symbolName] = rate;
			symbolName;
		};

		argList = Array.with(argNamesLocal, argRatesLocal);
		^argList;
	}

	// Retrieve the Bus object associated with an argument name.
	bus { |argName|
		^argBuses[argName.asSymbol];
	}

	// Shortcut to obtain only the Bus index.
	b { |argName|
		^this.bus(argName).index;
	}

	// ~car[\mod] -> ~car.bus(\mod)
	// - whereIn が nil の場合（ユーザが ~osc[\frq] のように 1 引数で呼ぶ経路）は
	//   \user を既定 where とする。
	// - 内部コードは whereIn を必ず渡して呼ぶ（補助案C）。
	at { |argName, whereIn|
		var argSymbol;
		var busLocal;
		var res;
		var rateSymbol;
		var serverLocal;
		var whereLocal;
		var xr;

		argSymbol = argName.asSymbol;
		whereLocal = (whereIn ? \user).asSymbol;

		// self-reference guard: while building this key, ~key[\*] is forbidden
		if((NdM.buildingKey.notNil) && (NdM.buildingKey == key)) {
			NdMError(
				"[NdM] self-reference detected while building key="
				++ key.asString
				++ " arg="
				++ argSymbol.asString
			).throw;
		};

		busLocal = this.bus(argSymbol);

		xr = nil;

		if(busLocal.notNil) {
			xr = busLocal;
		} {
			serverLocal = this.getServer;

			// unified resolution (recall both / inconsistent error / infer+remember both)
			res = NdM.resolveArgBus(key, argSymbol, serverLocal, whereLocal);
			busLocal = res[0];
			rateSymbol = res[1];

			// cache into instance maps (so subsequent calls are fast and rate-consistent)
			if(argBuses.isNil) {
				argBuses = IdentityDictionary.new;
			};
			if(argRates.isNil) {
				argRates = IdentityDictionary.new;
			};

			argBuses[argSymbol] = busLocal;
			argRates[argSymbol] = rateSymbol;

			xr = busLocal;
		};

		^xr;
	}

	// Read argument buses as UGen inputs, respecting their audio/control rate.
	readArgValues { |busesLocal, argNamesLocal, argRatesLocal|
		var busesDict;
		var namesAry;
		var ratesDict;
		var values;

		busesDict = if(busesLocal.isKindOf(IdentityDictionary)) { busesLocal } { IdentityDictionary.new };
		ratesDict = if(argRatesLocal.isKindOf(IdentityDictionary)) { argRatesLocal } { IdentityDictionary.new };
		namesAry = if(argNamesLocal.isKindOf(SequenceableCollection)) { argNamesLocal } { [] };

		values = namesAry.collect { |argName|
			var busLocal;
			var rateLocal;
			var xr;

			busLocal = busesDict[argName];
			rateLocal = ratesDict[argName] ? \audio;

			xr = if(busLocal.isNil) {
				nil
			} {
				if(rateLocal == \audio) {
					InFeedback.ar(busLocal.index, 1)
				} {
					In.kr(busLocal.index, 1)
				}
			};

			xr
		};
		this.debugPost("[DBG][READARG] values.class=" ++ values.class.asString);
		this.debugPost("[DBG][READARG] values.size=" ++ values.size.asString);
		this.debugPost("[DBG][READARG] values elem classes=" ++ values.collect { |v| v.class.asString }.asString);
		this.debugPost("[DBG][READARG] values=" ++ values.asString);
		values
	}

	// Resolve outbus specification (nil / Bus / Int / Array[Int]).
	// Always returns [busIndexOrArray, busRateSymbol].
	resolveOutBus { |outTarget|
		var busIndex;
		var busRate;
		var result;
		var isArray;
		var idxArray;
		var rateFirst;
		var hasRate;
		var hasError;
		var msg;
		var elem;
		var idx;
		var rateNow;

		// SPEC:
		// - Convert outTarget into [busIndex, busRate] used by writeSignalToBus.
		// - Accept raw-only inputs at this boundary: Integer / Array(Integer|Bus) / Bus / nil.
		// - No additional normalization here; NdM resolution is handled by NdMSpace before this call.
		// - On invalid type or structure:
		//     - reportError
		//     - increment category counter (diagnostic)
		//     - throw (to be swallowed by upper layers)
		//     - fallback to busIndex=0, busRate=audio at the caller’s responsibility.

		hasError = false;

		this.debugPost(
			"[NdM.resolveOutBus] stage=B src=outTarget key: " ++ key.asString
			++ "  outTarget: " ++ outTarget.asString
			++ "  class: " ++ outTarget.class.asString
		);

		if(outTarget.isNil) {
			busIndex = 0;
			busRate = \audio;
		} {
			if(outTarget.isKindOf(Bus)) {
				busIndex = outTarget.index;
				busRate = outTarget.rate;
			} {
				isArray = outTarget.isKindOf(Array);

				if(isArray) {
					if(outTarget.size <= 0) {
						NdMError("NdM: outbus array is empty").throw;
					};

					idxArray = Array.new;
					rateFirst = nil;
					hasRate = false;

					outTarget.do { |x|
						elem = x;
						idx = nil;
						rateNow = nil;

						if(elem.isKindOf(Bus)) {
							idx = elem.index;
							rateNow = elem.rate;
						} {
							if(elem.isKindOf(Integer)) {
								idx = elem;
								rateNow = \audio; // Integer is treated as audio out.
							} {
								msg = "NdM: outbus array element must be Bus or Integer, got: "
								++ elem.class.asString;
								NdMError(msg).throw;
							};
						};

						if(hasRate.not) {
							rateFirst = rateNow;
							hasRate = true;
						} {
							if(rateNow != rateFirst) {
								msg = "NdM: mixed bus rates in outbus array ("
								++ rateFirst.asString ++ " vs " ++ rateNow.asString ++ ")";
								NdMError(msg).throw;
							};
						};

						idxArray = idxArray.add(idx);
					};

					busIndex = idxArray;
					busRate = rateFirst ? \audio;
				} {
					// Integer is treated as audio out.
					if(outTarget.isKindOf(Integer)) {
						busIndex = outTarget;
						busRate = \audio;
					} {
						NdMError(
							"NdM: invalid outBus type: " ++ outTarget.class.asString
							++ " value=" ++ outTarget.asString
						).throw;
					};
				};
			};
		};

		this.debugPost(
			"[NdM.resolveOutBus] => busIndex: " ++ busIndex.asString
			++ "  busRate: " ++ busRate.asString
		);

		result = [busIndex, busRate];
		^result;
	}

	// Ensure output signal rate matches target bus's rate (ar/kr).
	checkRateMatch { |busRate, signalRate|

		if((busRate == \control) && (signalRate == \audio)) {
			NdMError(
				"NdM: rate mismatch (sig=ar, bus=kr)"
			).throw;
		};

		if((busRate == \audio) && (signalRate == \control)) {
			NdMError(
				"NdM: rate mismatch (sig=kr, bus=ar)"
			).throw;
		};
	}

	// Write the signal to the given busIndex (or Array of indices).
	// Multi-channel and multi-out behavior follows Out.kr / Out.ar.
	writeSignalToBus { |signalIn, busIndexIn, busRateIn|
		var signalLocal;
		var busIndexLocal;
		var busRateLocal;
		var outFunc;
		var indexCount;
		var chanCount;
		var idx;
		var sigChan;
		var elem;

		signalLocal = signalIn;
		busIndexLocal = busIndexIn;
		busRateLocal = busRateIn;

		this.debugPost(
			"[NdM.writeSignalToBus] key: " ++ key.asString
			++ "  signalClass: " ++ signalLocal.class.asString
			++ "  busIndex: " ++ busIndexLocal.asString
			++ "  busRate: " ++ busRateLocal.asString
		);

		if(busIndexLocal.isNil) {
			NdMError("NdM: resolved outbus index is nil").throw;
		};

		// Fix vX.Y.Z: C/D boundary guard (must catch outBus contamination here)
		if((busIndexLocal.isKindOf(Integer) || busIndexLocal.isKindOf(Array)).not) {

			NdMError.reportOutBus(
				\outbusC,
				"NdM.writeSignalToBus@C-guardType",
				key,
				\C,
				"busIndexIn",
				busIndexLocal,
				"fallback0"
			);

			busIndexLocal = 0;
		};

		if(busIndexLocal.isKindOf(Array)) {
			idx = 0;
			indexCount = busIndexLocal.size;
			while { idx < indexCount } {
				elem = busIndexLocal[idx];
				if(elem.isKindOf(Integer).not) {

					NdMError.reportOutBus(
						\outbusD,
						"NdM.writeSignalToBus@D-guardElem",
						key,
						\D,
						"busIndexIn",
						elem,
						"idx=" ++ idx.asString ++ " fallback0"
					);

					busIndexLocal = 0;
					idx = indexCount; // break
				} {
					idx = idx + 1;
				};
			};
		};

		outFunc = if(busRateLocal == \control) {
			{ |busNum, sig| Out.kr(busNum, sig) }
		} {
			{ |busNum, sig| Out.ar(busNum, sig) }
		};

		if(busIndexLocal.isKindOf(Array)) {
			indexCount = busIndexLocal.size;

			if(signalLocal.isKindOf(Array)) {
				chanCount = signalLocal.size;

				if(chanCount == indexCount) {
					// ch corresponding mapping (existing)
					idx = 0;
					while { idx < indexCount } {
						sigChan = signalLocal[idx];

						this.debugPost(
							"[NdM.writeSignalToBus] calling Out: bus="
							++ busIndexLocal[idx].asString
							++ " sigClass=" ++ sigChan.class.asString
						);

						outFunc.(busIndexLocal[idx], sigChan);
						idx = idx + 1;
					};
				} {
					// Size mismatch is compatible with previous versions.
					this.debugPost(
						"[NdM.writeSignalToBus] calling Out (array bus, mismatch): bus="
						++ busIndexLocal.asString
					);
					outFunc.(busIndexLocal, signalLocal);
				};
			} {
				// Do not duplicate mono signals.
				// Write mono signals separately on each bus (to prevent leaks)
				idx = 0;
				while { idx < indexCount } {
					this.debugPost(
						"[NdM.writeSignalToBus] calling Out (mono map): bus="
						++ busIndexLocal[idx].asString
					);
					outFunc.(busIndexLocal[idx], signalLocal);
					idx = idx + 1;
				};
			};
		} {
			this.debugPost(
				"[NdM.writeSignalToBus] calling Out (single bus): bus="
				++ busIndexLocal.asString
			);
			outFunc.(busIndexLocal, signalLocal);
		};

		signalIn
	}

	// Ensure this NdM is registered as a live node after NdMNameSpace.reset.
	// Policy: registry only (nodeSet). Do not rebuild graph/order here.
	ensureRegistered { |outbusIn|
		var monitor1;

		monitor1 = NdMNameSpace.acquire;
		monitor1.register(key, this);

		^this;
	}

	// Unified getter/setter interface for output bus routing.
	out { |val|
		var result;
		var monitor1;
		var oldOut;
		var oldRaw;
		var doRemoveOld;
		var ownerInfo;
		var outTarget;
		var rawOut;

		if(val.isNil) {
			result = outbus;
		} {
			oldOut = outbus;

			// raw-only: comparisons use raw Bus/Int/Array directly
			oldRaw = oldOut;

			outTarget = val;
			rawOut = outTarget;

			// Fix vX.Y.Z: A/C boundary guard (must not accept NdMSpace as outBus)
			if(rawOut.isKindOf(NdMSpace)) {
				NdMError.reportOutBus(
					\outbusA,
					"NdM.out_@A-guardType",
					key,
					\A,
					"val",
					rawOut,
					"fallback0"
				);
				rawOut = 0;
			};

			// store raw-only (A policy: raw-unify)
			outbus = rawOut;

			// GOAL:
			// - Use out / out_ as the primary trigger to deterministically re-register the node into NdMNameSpace first.
			// - Even after NdMNameSpace.reset, traversing the out path restores sink/edge records,
			//   ensuring that subsequent graph updates (markSink / registerEdge, etc.) operate consistently.
			// Primary trigger: ensure monitor registration before graph updates.
			this.ensureRegistered(rawOut);
			monitor1 = NdMNameSpace.acquire;

			if(monitor1.respondsTo(\markSink)) {

				// remove old edge if it existed
				doRemoveOld = false;

				if(oldRaw.isKindOf(Bus)) {
					if(rawOut.isKindOf(Bus)) {
						if(oldRaw.index != rawOut.index) {
							doRemoveOld = true;
						};
					} {
						doRemoveOld = true;
					};
				};

				if(doRemoveOld) {
					if(monitor1.respondsTo(\unregisterEdge)) {
						monitor1.unregisterEdge(key, oldRaw.index);
					};
				};

				// apply new sink/edge state
				if(rawOut.isKindOf(Bus)) {

					ownerInfo = nil;
					if(monitor1.respondsTo(\ensureOwnerForBusIndex)) {
						ownerInfo = monitor1.ensureOwnerForBusIndex(rawOut.index);
					} {
						if(monitor1.respondsTo(\ownerForBusIndex)) {
							ownerInfo = monitor1.ownerForBusIndex(rawOut.index);
						};
					};

					if(ownerInfo.isNil) {
						// external bus => sink
						monitor1.markSink(key);
					} {
						// known owner => edge, not a sink
						if(monitor1.respondsTo(\unmarkSink)) {
							monitor1.unmarkSink(key);
						};
						if(monitor1.respondsTo(\registerEdge)) {
							monitor1.registerEdge(key, rawOut.index);
						};
					};

				} {
					if(rawOut.isKindOf(Integer) || rawOut.isKindOf(Array)) {
						monitor1.markSink(key);
					} {
						if(monitor1.respondsTo(\unmarkSink)) {
							monitor1.unmarkSink(key);
						};
					};
				};
			};

			this.updateProxyOut('reason:outChanged');
			result = this;
		};

		^result;
	}

	// Setter-only version for property assignment syntax.
	out_ { |val|
		var monitor1;
		var oldOut;
		var oldRaw;
		var doRemoveOld;
		var ownerInfo;
		var outTarget;
		var rawOut;

		oldOut = outbus;

		// raw-only: out_ assumes raw Bus/Int/Array directly
		oldRaw = oldOut;

		outTarget = val;
		rawOut = outTarget;

		// Fix vX.Y.Z: A/C boundary guard (must not accept NdMSpace as outBus)
		if(rawOut.isKindOf(NdMSpace)) {

			NdMError.reportOutBus(
				\outbusA,
				"NdM.out_@A-guardType",
				key,
				\A,
				"val",
				rawOut,
				"fallback0"
			);

			rawOut = 0;
		};

		// store raw only (A 方針)
		outbus = rawOut;

		// GOAL:
		// - Ensure that the setter path (out_) guarantees the same “primary trigger” ordering as out
		//   (re-registration first, then graph updates).
		this.ensureRegistered(rawOut);

		monitor1 = NdMNameSpace.acquire;

		if(monitor1.respondsTo(\markSink)) {

			// remove old edge if it existed
			doRemoveOld = false;

			if(oldRaw.isKindOf(Bus)) {
				if(rawOut.isKindOf(Bus)) {
					if(oldRaw.index != rawOut.index) {
						doRemoveOld = true;
					};
				} {
					doRemoveOld = true;
				};
			};

			if(doRemoveOld) {
				if(monitor1.respondsTo(\unregisterEdge)) {
					monitor1.unregisterEdge(key, oldRaw.index);
				};
			};

			// apply new sink/edge state
			if(rawOut.isKindOf(Bus)) {

				ownerInfo = nil;
				if(monitor1.respondsTo(\ensureOwnerForBusIndex)) {
					ownerInfo = monitor1.ensureOwnerForBusIndex(rawOut.index);
				} {
					if(monitor1.respondsTo(\ownerForBusIndex)) {
						ownerInfo = monitor1.ownerForBusIndex(rawOut.index);
					};
				};

				if(ownerInfo.isNil) {
					// external bus => sink
					monitor1.markSink(key);
				} {
					// known owner => edge, not a sink
					if(monitor1.respondsTo(\unmarkSink)) {
						monitor1.unmarkSink(key);
					};
					if(monitor1.respondsTo(\registerEdge)) {
						monitor1.registerEdge(key, rawOut.index);
					};
				};

			} {
				if(rawOut.isKindOf(Integer) || rawOut.isKindOf(Array)) {
					monitor1.markSink(key);
				} {
					if(monitor1.respondsTo(\unmarkSink)) {
						monitor1.unmarkSink(key);
					};
				};
			};
		};

		this.updateProxyOut('reason:outChanged');
	}

	// Rebuild proxy source (to capture updated outbus) without restarting playback.
	updateProxyOut { |reason|
		var proxyLocal;
		var proxyFunc;
		var spaceLocal;
		var reasonSym;

		spaceLocal = NdMSpace.current;

		reasonSym = reason;
		if(reasonSym.isNil) {
			reasonSym = 'reason:none';
		};

		if(spaceLocal.notNil) {
			spaceLocal.tracePush(
				'updateProxyOut.enter',
				key,
				\none,
				reqGen,
				\none,
				-1,
				reasonSym,
				-1
			);
		};

		proxyLocal = proxy;
		if(proxyLocal.notNil) {
			// Generate a fresh proxyFunc that references the new outbus state.
			proxyFunc = this.makeProxyFunc;
			proxyLocal.source = proxyFunc;
		};
	}


	// short aliases (live coding convenience)

	o { |outTarget|
		^this.out(outTarget);
	}

	f { |fadeTimeIn|
		^this.fade(fadeTimeIn);
	}

	t { |tagSymbol|
		^this.tag(tagSymbol.asSymbol);
	}

	p {
		^this.play;
	}

	s {
		^this.stop;
	}
}