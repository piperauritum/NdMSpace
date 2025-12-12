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

	// var fadeStart;
	// var fadeEnd;
	// var fadeDur;
	// var freeSeq;

	// Crossfade duration used when switching functions (ndmGate based).
	var <>switchDur;

	// Tracks whether initial fade setup has been completed for this instance.
	var hasFadeInit;

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
	*new { |key, func, outbus|
		var instance;
		var keySymbol;
		var result;
		var hasOld;
		var outLabel;
		var debugMessage;

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
			debugMessage =　debugMessage ++　"[NdM.new] reuse existing instance; call setFunc\n";
			instance.setFunc(func, outbus);
			result = instance;
			^result;
		};

		debugMessage =　debugMessage ++　"[NdM.new] create new instance";

		// == Log for development == //
		// debugMessage.postln;

		instance = super.new;
		instance.init(keySymbol, func, outbus);

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

	// Main initializer: setup key, function, argument metadata, and bus allocation.
	init { |keySymbol, funcIn, outbusIn|
		var server;
		// var monitorLocal;
		var monitor1;
		var keyString;
		var parsedArgs;
		var rateLabel;
		var debugMessage;

		debugMessage =　("[NdM.init] keySymbol: " ++ keySymbol.asString
			++ "  outbusIn: " ++ outbusIn.asString
		);

		// == Log for development == //
		// debugMessage.postln;

		key = keySymbol;
		func = funcIn;
		outbus = outbusIn;
		fadetime = 0.01;					// Default fade time before user modification.
		fadeGap = NdM.fadeGap ?? 0.04;		// Delay between fade start and amp update.
		hasFadeInit = false;				// Track if initial fade setup has run.

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

		// Acquire modern namespace monitor (bus/mapping handler).
		monitor1 = NdMNameSpace.acquire;
		monitor1.register(key, this);

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
			if(monitor1.notNil) {
				busIndex = monitor1.recallBus(key, argName);
			};

			// If no reusable bus found, allocate a new one.
			// if(busIndex.isNil) {
			// 	if(monitorLocal.notNil) {
			// 		busIndex = monitorLocal.lookupBusIndex(key, argName);
			// 	};
			// };


			if(busIndex.notNil) {
				bus = Bus.new(rate, busIndex, 1, server);
			} {
				if(rate == \audio) {
					bus = Bus.audio(server, 1);
				} {
					bus = Bus.control(server, 1);
				};

				// --- obsolete: legacy NdMMon bus table ---
				// if(monitorLocal.notNil) {
				// 	monitorLocal.storeBusIndex(key, argName, bus.index);
				// };
			};

			argBuses[argName] = bus;

			// Update namespace with latest bus/rate information.
			if(monitor1.notNil) {
				monitor1.rememberBus(key, argName, bus.index);
				monitor1.rememberRate(key, argName, rate);
			};
		};

		// Proxy is instantiated here, but source is set only at play-time.
		proxy = Ndef(key);

		// Initialize fade-related fields.
		fadeBus = nil;
		// fadeStart = 0.0;
		// fadeEnd = 0.0;
		// fadeDur = 0.0;

		// freeSeq = 0;

		switchDur = 0.05;	// Default crossfade duration for function switching.

		pollFreq = 10;		// Default polling frequency

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
	at { |argName|
		var symbol;
		var busLocal;

		symbol = argName.asSymbol;
		busLocal = this.bus(symbol);
		^busLocal;
	}

	// Read argument buses as UGen inputs, respecting their audio/control rate.
	readArgValues { |busesLocal, argNamesLocal, argRatesLocal|
		var values;

		values = argNamesLocal.collect { |argName|
			var busLocal;
			var rateLocal;

			busLocal = busesLocal[argName];
			rateLocal = argRatesLocal[argName] ? \audio;

			// InFeedback.ar gives stable audio-rate modulation with 1-block latency.
			if(rateLocal == \audio) {
				InFeedback.ar(busLocal.index, 1);
			} {
				In.kr(busLocal.index, 1);
			};
		};

		^values;
	}

	// Resolve outbus specification (nil / Bus / Int / Array[Int]).
	// Always returns [busIndexOrArray, busRateSymbol].
	resolveOutBus { |outbusLocal|
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

		hasError = false;

		this.debugPost(
			"[NdM.resolveOutBus] key: " ++ key.asString
			++ "  outbusLocal: " ++ outbusLocal.asString
			++ "  class: " ++ outbusLocal.class.asString
		);

		if(outbusLocal.isNil) {
			busIndex = 0;
			busRate = \audio;
		} {
			if(outbusLocal.isKindOf(Bus)) {
				busIndex = outbusLocal.index;
				busRate = outbusLocal.rate;
			} {
				isArray = outbusLocal.isKindOf(Array);

				if(isArray) {
					if(outbusLocal.size <= 0) {
						NdMError("NdM: outbus array is empty").throw;
					};

					idxArray = Array.new;
					rateFirst = nil;
					hasRate = false;

					outbusLocal.do { |x|
						elem = x;
						idx = nil;
						rateNow = nil;

						if(elem.isKindOf(Bus)) {
							idx = elem.index;
							rateNow = elem.rate;
						} {
							if(elem.isKindOf(Integer)) {
								idx = elem;
								rateNow = \audio; // integer は audio out とみなす
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
					// Integer 等：audio out 扱い
					busIndex = outbusLocal;
					busRate = \audio;
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

	// resolveOutBus { |outbusLocal|
	// 	var busIndex;
	// 	var busRate;
	// 	var result;
	//
	// 	if(outbusLocal.isNil) {
	// 		busIndex = 0;
	// 		busRate = \audio;
	// 	} {
	// 		if(outbusLocal.isKindOf(Bus)) {
	// 			busIndex = outbusLocal.index;
	// 			busRate = outbusLocal.rate;
	// 		} {
	// 			// Integers/arrays imply audio-rate output.
	// 			busIndex = outbusLocal;
	// 			busRate = \audio;
	// 		};
	// 	};
	//
	// 	result = [busIndex, busRate];
	// 	^result;
	// }

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
					// ch 対応マッピング（既存）
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
					// サイズ不一致は従来互換
					this.debugPost(
						"[NdM.writeSignalToBus] calling Out (array bus, mismatch): bus="
						++ busIndexLocal.asString
					);
					outFunc.(busIndexLocal, signalLocal);
				};
			} {
				// ★ mono signal: dup しない。各バスに mono を個別に書く（リーク防止）
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

	// writeSignalToBus { |signal, busIndex, busRate|
	// 	if(busRate == \control) {
	// 		Out.kr(busIndex, signal);
	// 	} {
	// 		Out.ar(busIndex, signal);
	// 	};
	// }

	// Unified getter/setter interface for output bus routing.
	out { |val|
		var result;

		if(val.isNil) {
			// Getter: returns current outbus value.
			result = outbus;
		} {
			// Setter: update outbus and immediately refresh proxy source.
			outbus = val;
			this.updateProxyOut;
			result = this;
		};

		^result;
	}

	// Setter-only version for property assignment syntax.
	out_ { |val|
		outbus = val;
		this.updateProxyOut;
	}

	// Rebuild proxy source (to capture updated outbus) without restarting playback.
	updateProxyOut {
		var proxyLocal;
		var proxyFunc;

		proxyLocal = proxy;
		if(proxyLocal.notNil) {
			// Generate a fresh proxyFunc that references the new outbus state.
			proxyFunc = this.makeProxyFunc;
			proxyLocal.source = proxyFunc;
		};
	}

	/*	isPlaying {
	var proxyLocal;
	var flag;

	proxyLocal = proxy;
	if(proxyLocal.isNil) {
	flag = false;
	} {
	flag = proxyLocal.isPlaying;
	};

	^flag;
	}*/
}