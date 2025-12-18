/* ============================================================
NdMSpace — ProxySpace-like front-end for NdM
============================================================ */

NdMSpace : LazyEnvir {

	classvar <current;

	var server;

	// New: carry last requested dbg value (normalized to 0/1), or nil if unspecified.
	var <>lastDbgValue;

	// --------------------------------------------------------------------
	// Frontend: global NdM control
	// --------------------------------------------------------------------

	*stopAll {
		NdM.stopAll;
		^this;
	}

	*freeAll {
		NdM.freeAll;
		^this;
	}

	*dump {
		NdMNameSpace.dumpStatus;
		^this;
	}

	*dumpKey { |keySymbol|
		NdMNameSpace.dumpKey(keySymbol);
		^this;
	}

	*reset {
		NdMNameSpace.reset;
		^this;
	}

	// Stop+free+namespace reset in one call (optional helper).
	*clean {
		NdM.freeAll;
		NdMNameSpace.reset;
		^this;
	}

	// --------------------------------------------------------------------
	// Frontend: tag-based group control
	// --------------------------------------------------------------------

	*playTag { |tagSymbol|
		NdMTag.play(tagSymbol);
		^this;
	}

	*stopTag { |tagSymbol|
		NdMTag.stop(tagSymbol);
		^this;
	}

	*freeTag { |tagSymbol|
		NdMTag.free(tagSymbol);
		^this;
	}

	*nodesForTag { |tagSymbol|
		^NdMTag.nodesForTag(tagSymbol);
	}

	*tags {
		^NdMTag.tags;
	}

	// --------------------------------------------------------------------
	// Frontend: NdM instances / status helpers
	// --------------------------------------------------------------------

	// Get NdM instance by key (or nil if not found).
	*get { |key|
		var instance;
		instance = NdM.find(key);
		^instance;
	}

	// --------------------------------------------------------------------
	// Frontend: current NdM registry view
	// --------------------------------------------------------------------

	// Return an Array of all active NdM instances.
	*nodes {
		var table;
		table = NdM.instances;
		if(table.isNil) {
			^Array.new;
		};
		^table.values;
	}

	// Simple alias to dump the current namespace status.
	*status {
		var table;
		var keys;
		var node;
		var hashVal;

		table = NdM.instances;

		if(table.isNil or: { table.isEmpty }) {
			"===== NdMSpace status =====".postln;
			"no active NdM nodes".postln;
			"===========================".postln;
			^this;
		};

		"===== NdMSpace status =====".postln;

		keys = table.keys.asArray.sort;
		keys.do { |key|
			node = table[key];
			hashVal = node.identityHash;
			("name: " ++ key.asString
				++ "  hash: " ++ hashVal.asString).postln;
		};

		"===========================".postln;
		^this;
	}

	// --------------------------------------------------------------------
	// Instance-side shortcuts (NodeProxy-style)
	// --------------------------------------------------------------------

	// Tag-based group control (instance front-end)

	playTag { |tagSymbol|
		NdMTag.play(tagSymbol);
		^this;
	}

	stopTag { |tagSymbol|
		NdMTag.stop(tagSymbol);
		^this;
	}

	freeTag { |tagSymbol|
		NdMTag.free(tagSymbol);
		^this;
	}

	tags {
		^NdMTag.tags;
	}

	// NdM global control (instance front-end)

	stopAll {
		NdM.stopAll;
		^this;
	}

	freeAll {
		NdM.freeAll;
		^this;
	}

	dump {
		NdMNameSpace.dumpStatus;
		^this;
	}

	dumpKey { |keySymbol|
		NdMSpace.dumpKey(keySymbol);
		^this;
	}

	reset {
		NdMNameSpace.reset;
		^this;
	}

	// New: instance-side nodesForTag / clean / status / get / nodes / fadeGap

	nodesForTag { |tagSymbol|
		^NdMSpace.nodesForTag(tagSymbol);
	}

	clean {
		NdMSpace.clean;
		^this;
	}

	status {
		NdMSpace.status;
		^this;
	}

	get { |key|
		^NdMSpace.get(key);
	}

	nodes {
		^NdMSpace.nodes;
	}

	fadeGap {
		^NdM.fadeGap;
	}

	fadeGap_ { |value|
		NdM.fadeGap = value;
		^this;
	}

	// New: dbg carry API (NdMSpace-level)
	//
	// a = NdMSpace.enter;
	// a.dbg(1);     // store lastDbgValue = 1
	// a.dbg(0);     // store lastDbgValue = 0
	// a.dbg;        // -> lastDbgValue (nil/0/1)
	dbg { |value|
		var norm;
		var table;

		// getter
		if(value.isNil) {
			^lastDbgValue;
		};

		// setter (normalize to 0/1)
		if(value.isNumber) {
			if(value > 0) {
				norm = 1;
			} {
				norm = 0;
			};
		} {
			if(value == true) {
				norm = 1;
			} {
				norm = 0;
			};
		};

		lastDbgValue = norm;

		// Apply to existing NdM instances immediately (so T1 works).
		table = NdM.instances;
		if(table.notNil) {
			table.values.do { |ndmInstance|
				if(ndmInstance.notNil) {
					ndmInstance.dbg(norm);
				};
			};
		};

		^this;
	}

	dbg_ { |value|
		var norm;
		var table;

		if(value.isNumber) {
			if(value > 0) {
				norm = 1;
			} {
				norm = 0;
			};
		} {
			if(value == true) {
				norm = 1;
			} {
				norm = 0;
			};
		};

		lastDbgValue = norm;

		// Apply to existing NdM instances immediately.
		table = NdM.instances;
		if(table.notNil) {
			table.values.do { |ndmInstance|
				if(ndmInstance.notNil) {
					ndmInstance.dbg(norm);
				};
			};
		};
	}

	exit {
		^NdMSpace.exit;
	}

	// --------------------------------------------------------------------
	// Main
	// --------------------------------------------------------------------

	*enter { |inServer|
		var oldEnv;
		var space;
		var srv;

		oldEnv = currentEnvironment;
		space = this.new;

		srv = inServer;
		if(srv.isNil) {
			srv = Server.default;
		};

		// Then call the instance method
		space.initForServer(srv);

		// Copy the contents of the old Environment into a "raw dictionary"
		oldEnv.keysValuesDo { |key, value|
			space.envir.put(key, value);
		};

		current = space;

		space.push;

		"===== NdMSpace.enter =====".postln;
		("previous environment: " ++ oldEnv.asString).postln;
		("new NdMSpace: " ++ space.asString).postln;
		("server: " ++ srv.asString).postln;
		"==========================".postln;

		^space;
	}

	*exit {
		var space;

		space = current;
		if(space.notNil) {
			space.pop;
		};

		current = nil;

		"===== NdMSpace.exit =====".postln;
		"NdMSpace cleared and environment restored.".postln;
		"=========================".postln;

		^space;
	}

	initForServer { |srv|
		server = srv;
		lastDbgValue = nil;
	}

	put { |key, obj|
		var specObj;
		var oldVal;
		var ndmObj;
		var outBus;
		var inheritDbg;
		var norm;

		oldVal = envir.at(key);

		// 1) In the case of NdMSpaceSpec → Generate NdM
		if(obj.isKindOf(NdMSpaceSpec)) {

			specObj = obj;

			// (1) Apply lastDbgValue if spec does not explicitly define dbg.
			if(specObj.dbgValue.isNil && lastDbgValue.notNil) {
				specObj.dbg(lastDbgValue);
			};

			// (2) Existing fallback: inherit dbg from previous NdM (only if still unspecified).
			inheritDbg = nil;
			if(oldVal.isKindOf(NdM) && specObj.dbgValue.isNil) {
				// NdM.dbg getter returns Boolean (canDebug).
				inheritDbg = oldVal.dbg;
				if(inheritDbg == true) {
					specObj.dbg(1);
				};
			};

			// If outBus is explicitly specified, prefer it.
			if(specObj.outBus.notNil) {
				outBus = specObj.outBus;
			} {
				// Otherwise, inherit outbus from existing NdM, or fall back to 0.
				if(oldVal.isKindOf(NdM)) {
					outBus = oldVal.out;
				} {
					outBus = 0;
				};
			};

			ndmObj = NdM(key, specObj.func, outBus);

			if(specObj.dbgValue.notNil) {
				ndmObj.dbg(specObj.dbgValue);

				// If dbg was explicitly specified on spec, remember it as lastDbgValue.
				if(specObj.dbgValue.isNumber) {
					if(specObj.dbgValue > 0) {
						norm = 1;
					} {
						norm = 0;
					};
				} {
					if(specObj.dbgValue == true) {
						norm = 1;
					} {
						norm = 0;
					};
				};
				lastDbgValue = norm;

			} {
				if(oldVal.isKindOf(NdM)) {
					if(oldVal.dbg) {
						ndmObj.dbg(1);
					};
				};
			};

			if(specObj.fadeTime > 0) {
				ndmObj.fade = specObj.fadeTime;
			};

			specObj.applyTagsTo(ndmObj);

			envir.put(key, ndmObj);

			if(specObj.autoPlay) {
				ndmObj.play;
			};

			^ndmObj;
		};

		// 2) A raw Function is not converted to NdM; store it as-is.
		if(obj.isKindOf(Function)) {
			// If the previous value was an NdM, free it first (stop sound).
			if(oldVal.isKindOf(NdM)) {
				oldVal.free;
			};

			envir.put(key, obj);
			^obj;
		};

		// 3) Other objects (e.g. String) are stored as-is.
		//    If the previous value was an NdM, free it before overwriting.
		if(oldVal.isKindOf(NdM)) {
			oldVal.free;
		};

		envir.put(key, obj);
		^obj;
	}



	makeProxy { |key|
		var ndmObj;

		ndmObj = envir.at(key);
		if(ndmObj.isKindOf(NdM)) {
			^ndmObj;
		};

		ndmObj = NdM(key, { Silent.ar(1) }, 0);
		envir.put(key, ndmObj);
		^ndmObj;
	}

	// --------------------------------------------------------------------
	// Backend: NdMSpaceSpec (builder for NdM creation)
	// --------------------------------------------------------------------

	*fromFunction { |func|
		^NdMSpaceSpec.fromFunction(func);
	}
}


/* ============================================================
NdMSpaceSpec — lightweight spec holder for NdMSpace

FIELDS:
- func      : Function (UGen graph)
- outBus    : out bus (Integer or Bus or nil)
- fadeTime  : fade duration in seconds (Float or Integer)
- autoPlay  : Boolean (true = NdM.play after creation)
- tagBuffer : Array of Symbol (tags to apply after NdM creation)

USAGE:
~osc = nd { SinOsc.ar(440, 0, 0.1) }.out(0).fade(2).tag(\bg).play;
============================================================ */

NdMSpaceSpec : Object {

	var <>func;
	var <>outBus;
	var <>fadeTime;
	var <>autoPlay;
	var <>tagBuffer;
	var <>dbgValue;

	// ------------------------------------------------
	// Construction
	// ------------------------------------------------

	*fromFunction { |inFunc|
		var spec;
		spec = this.new;
		spec.func = inFunc;
		spec.outBus = nil;
		spec.fadeTime = 0;
		spec.autoPlay = false;
		spec.tagBuffer = nil;
		^spec;
	}

	// ------------------------------------------------
	// Basic configuration
	// ------------------------------------------------

	out { |outBusIn|
		outBus = outBusIn;
		^this;
	}

	fade { |fadeTimeIn|
		fadeTime = fadeTimeIn;
		^this;
	}

	play {
		autoPlay = true;
		^this;
	}

	// aliases (for NdMSpaceSpec builder)
	o { |outBusIn|
		^this.out(outBusIn);
	}

	f { |fadeTimeIn|
		^this.fade(fadeTimeIn);
	}

	p {
		^this.play;
	}

	// ------------------------------------------------
	// Tag configuration (deferred)
	// ------------------------------------------------

	tag { |tagSymbol|
		if(tagBuffer.isNil) {
			tagBuffer = Array.new;
		};
		tagBuffer = tagBuffer.add(tagSymbol);
		^this;   // chainable
	}

	untag { |tagSymbol|
		if(tagBuffer.notNil) {
			tagBuffer.remove(tagSymbol);
		};
		^this;
	}

	// aliases (for NdMSpaceSpec builder)
	t { |tagSymbol|
		^this.tag(tagSymbol);
	}

	// Called after NdM creation to apply buffered tags
	applyTagsTo { |ndmInstance|
		if(tagBuffer.notNil) {
			tagBuffer.do { |tagItem|
				ndmInstance.tag(tagItem);
			};
		};
	}

	dbg { |value|
		var xr;

		// getter
		if(value.isNil) {
			xr = dbgValue;
		} {
			dbgValue = value;
			xr = this;
		};

		^xr;
	}

	dbg_ { |value|
		dbgValue = value;
	}
}


// Function → NdMSpaceSpec front-end

+ Function {
	nd {
		^NdMSpaceSpec.fromFunction(this);
	}
}
