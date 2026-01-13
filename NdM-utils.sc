NdMError : Error {

	// ============================================================
	// Error counter (class-level)
	// ============================================================

	classvar <errorCount;
	classvar <countsByCategory;

	*initClass {
		errorCount = 0;
		this.resetCounts;
	}

	*resetCount {
		errorCount = 0;
		^this;
	}

	*resetCounts {
		countsByCategory = IdentityDictionary.new;
		^this;
	}

	*resetAllCounts {
		this.resetCount;
		this.resetCounts;
		^this;
	}

	*incrementCount {
		errorCount = (errorCount ? 0) + 1;
		^this;
	}

	*incrementCategory { |catSymbol|
		var catLocal;
		var dictLocal;
		var prevCount;

		catLocal = catSymbol;
		if(catLocal.isNil) {
			catLocal = \nilCategory;
		};

		dictLocal = countsByCategory;
		if(dictLocal.isNil) {
			dictLocal = IdentityDictionary.new;
			countsByCategory = dictLocal;
		};

		prevCount = dictLocal[catLocal] ? 0;
		dictLocal[catLocal] = prevCount + 1;

		^this;
	}

	*countFor { |catSymbol|
		var catLocal;
		var dictLocal;
		var result;

		catLocal = catSymbol;
		if(catLocal.isNil) {
			catLocal = \nilCategory;
		};

		dictLocal = countsByCategory;
		if(dictLocal.isNil) {
			result = 0;
		} {
			result = dictLocal[catLocal] ? 0;
		};

		^result;
	}

	*countsSnapshot {
		var dictLocal;
		var result;

		dictLocal = countsByCategory;
		if(dictLocal.isNil) {
			result = IdentityDictionary.new;
		} {
			result = dictLocal.copy;
		};

		^result;
	}

	*dumpCounts { |label|
		var labelLocal;
		var snap;
		var msg;

		labelLocal = label;
		if(labelLocal.isNil) {
			labelLocal = "";
		};

		snap = this.countsSnapshot;
		msg = "[NdMError.counts] " ++ labelLocal.asString ++ " " ++ snap.asString;
		msg.postln;

		^this;
	}

	*valueDump { |valObj|
		var valLocal;
		var result;
		var tmp;

		valLocal = valObj;

		if(valLocal.isNil) {
			result = "nil";
		} {
			tmp = try {
				valLocal.asCompileString;
			} {
				nil;
			};

			if(tmp.isNil) {
				tmp = try {
					valLocal.asString;
				} {
					"<unprintable>";
				};
			};

			result = tmp.asString;
		};

		^result;
	}

	*formatOutBusMsg { |whereString, keySymbol, stageSymbol, srcString, valObj, noteString|
		var whereLocal;
		var keyLocal;
		var stageLocal;
		var stageStr;
		var srcLocal;
		var valLocal;
		var clsStr;
		var dumpStr;
		var noteLocal;
		var msg;

		whereLocal = whereString;
		if(whereLocal.isNil) {
			whereLocal = "(nilWhere)";
		};

		keyLocal = keySymbol;
		if(keyLocal.isNil) {
			keyLocal = \nilKey;
		};

		stageLocal = stageSymbol;
		if(stageLocal.isNil) {
			stageLocal = \nilStage;
		};

		stageStr = stageLocal.asString;
		if(stageStr.size > 1) {
			stageStr = stageStr[0].asString;
		};

		srcLocal = srcString;
		if(srcLocal.isNil) {
			srcLocal = "";
		};

		valLocal = valObj;
		if(valLocal.isNil) {
			clsStr = "Nil";
		} {
			clsStr = valLocal.class.asString;
		};

		dumpStr = this.valueDump(valLocal);

		noteLocal = noteString;
		if(noteLocal.isNil) {
			noteLocal = "";
		};

		msg =
		"[ERR][OUTBUS] key=" ++ keyLocal.asString
		++ " where=" ++ whereLocal.asString
		++ " stage=" ++ stageStr.asString
		++ " src=" ++ srcLocal.asString
		++ " class=" ++ clsStr.asString
		++ " valueDump=" ++ dumpStr.asString
		++ " note=" ++ noteLocal.asString;

		^msg;
	}

	*reportOutBus { |catSymbol, whereString, keySymbol, stageSymbol, srcString, valObj, noteString|
		var msg;

		this.incrementCategory(catSymbol);
		msg = this.formatOutBusMsg(whereString, keySymbol, stageSymbol, srcString, valObj, noteString);
		NdMError(msg).reportError;

		^this;
	}

	*assertCountZero { |label|
		var cnt;
		var msg;

		cnt = errorCount;

		if(cnt > 0) {
			msg = "[TEST][FAIL] " ++ label.asString ++ " NdMError.errorCount=" ++ cnt.asString;
			msg.postln;
			Error(msg).throw;
		};

		^this;
	}

	// Ensure the string argument is passed to Error's constructor
	*new { |string|
		this.incrementCount;
		^super.new(string);
	}

	// Print only a concise, NdM-specific message (no stack trace)
	reportError {
		("[NdMError] " ++ this.errorString).postln;
	}
}


/* ============================================================
NdMTag — Tag-based NdM group operations

PURPOSE:
- Provide tag-oriented operations on NdM instances.
- Wrap NdMNameSpace low-level tag APIs with
higher-level behaviors such as stop/free by tag.

USAGE EXAMPLES:
- NdMTag.stop(\bg);
- NdMTag.free(\fx);
- NdMTag.nodesForTag(\bg);  // -> Array[NdM, ...]
- NdMTag.tags;              // -> Array[Symbol, ...]
============================================================ */

NdMTag : Object {

	*play { |tagSymbol|
		var nodes;
		var ndmInstance;

		// Retrieve all NdM instances that have the given tag via NdMNameSpace.
		nodes = this.nodesForTag(tagSymbol);

		// Call .play on each instance (using the current fade settings of NdM).
		nodes.do { |ndmInstance|
			ndmInstance.play;
		};

		^this;
	}

	*stop { |tagSymbol|
		var nodes;

		nodes = this.nodesForTag(tagSymbol);
		nodes.do { |ndmInstance|
			if(ndmInstance.notNil) {
				ndmInstance.stop;
			};
		};

		^this;
	}

	*free { |tagSymbol|
		var nodes;

		nodes = this.nodesForTag(tagSymbol);
		nodes.do { |ndmInstance|
			if(ndmInstance.notNil) {
				ndmInstance.free;
			};
		};

		^this;
	}

	*nodesForTag { |tagSymbol|
		var monitor;
		var nodes;

		monitor = NdMNameSpace.acquire;
		nodes = monitor.nodesForTag(tagSymbol);
		^nodes;
	}

	*tags {
		var monitor;
		var result;

		monitor = NdMNameSpace.acquire;
		result = monitor.allTags;
		^result;
	}
}


+ NdM {

	// ============================================================
	// Tag API (instance)
	// ============================================================
	//
	//   ~osc.tag(\bg)     -> add tag, return this (chainable)
	//   ~osc.untag(\bg)   -> remove tag, return this
	//   ~osc.tag          -> get current tags as Array
	//
	// Storage and reverse lookup are delegated to NdMNameSpace.

	tag { |tagSymbol|
		var monitor;
		var result;

		monitor = NdMNameSpace.acquire;

		if(tagSymbol.isNil) {
			// getter: return current tags for this key
			result = monitor.tagsFor(key);
		} {
			// setter: add tag to this key
			monitor.addTag(key, tagSymbol);
			result = this;
		};

		^result;
	}

	untag { |tagSymbol|
		var monitor;

		monitor = NdMNameSpace.acquire;
		monitor.removeTag(key, tagSymbol);
		^this;
	}
}


+ NdM {

	// ============================================================
	// 1. Core debug utilities
	// ============================================================

	// Print debug message only when canDebug is true.
	debugPost { |message|
		var flag;
		flag = canDebug ? false;
		if(flag) {
			message.postln;
		};
	}

	// Attach poll UGen only when canPoll is true.
	conditionalPoll { |ugen, freq = 10, label = ""|
		var flag;
		flag = canPoll ? false;
		if(flag) {
			ugen.poll(freq, label);
		};
	}


	// ============================================================
	// 2. dbg flag API (instance only)
	// ============================================================
	//
	//   ~osc.dbg        -> get Boolean (default: false when nil)
	//   ~osc.dbg(1)     -> set flag, return this (chainable)
	//   ~osc.dbg = 1;   -> set flag only
	//
	// Number : value > 0 -> true
	// Other  : value == true -> true

	dbg { |value|
		var flag;

		// getter
		if(value.isNil) {
			^(canDebug ? false);
		};

		// setter
		if(value.isNumber) {
			flag = (value > 0);
		} {
			flag = (value == true);
		};

		canDebug = flag;
		^this;
	}

	dbg_ { |value|
		var flag;

		if(value.isNumber) {
			flag = (value > 0);
		} {
			flag = (value == true);
		};

		canDebug = flag;
	}


	// ============================================================
	// 3. poll flag API (instance only)
	// ============================================================
	//
	//   ~osc.poll        -> get Boolean (default: false when nil)
	//   ~osc.poll(1)     -> set + rebuild proxy graph + this
	//   ~osc.poll = 1;   -> set + rebuild proxy graph
	//
	// Poll flag affects whether conditionalPoll() actually
	// inserts poll UGens into the synth graph.

	poll { |value|
		var flag;

		// getter
		if(value.isNil) {
			^(canPoll ? false);
		};

		// setter
		if(value.isNumber) {
			flag = (value > 0);
		} {
			flag = (value == true);
		};

		canPoll = flag;

		// Rebuild proxy graph so that poll UGens are updated.
		this.updateProxyOut;

		^this;
	}

	poll_ { |value|
		var flag;

		if(value.isNumber) {
			flag = (value > 0);
		} {
			flag = (value == true);
		};

		canPoll = flag;
		this.updateProxyOut;
	}


	// ============================================================
	// 4. Server helper
	// ============================================================

	// Retrieve the server associated with this NdM instance.
	// If proxy exists use its server; otherwise fall back to Server.default.
	getServer {
		var proxyLocal;
		var serverLocal;
		var result;

		proxyLocal = proxy;

		if(proxyLocal.notNil) {
			serverLocal = proxyLocal.tryPerform(\server);
		} {
			serverLocal = nil;
		};

		if(serverLocal.isNil) {
			serverLocal = Server.default;
		};

		result = serverLocal;
		this.debugPost("[NdM getServer] server: " ++ result.asString);
		^result;
	}


	// ============================================================
	// 5. Final cleanup helper (currently used only in free logic)
	// ============================================================

	doFinalFreeCleanup {
		var monitorLocal;

		if(proxy.notNil) {
			proxy.clear;
			proxy = nil;
		};

		if(argBuses.notNil) {
			argBuses.do { |busObject|
				if(busObject.notNil) {
					busObject.free;
				};
			};
			argBuses = nil;
		};

		monitorLocal = NdMNameSpace.instance;
		if(monitorLocal.notNil) {
			monitorLocal.unregister(key, this);
		};

		if(key.notNil && { instances.notNil }) {
			instances.removeAt(key);
		};
	}
}
