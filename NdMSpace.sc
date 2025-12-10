/* ============================================================
NdMSpace — ProxySpace-like front-end for NdM
============================================================ */

NdMSpace : LazyEnvir {

	classvar <current;

	var server;

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

		// ここで instance メソッドを呼ぶ
		space.initForServer(srv);

		// 旧 Environment の内容を「生の辞書」にコピー
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
	}

	put { |key, obj|
		var specObj;
		var oldVal;
		var ndmObj;
		var outBus;
		// var oldIsPlaying;

		oldVal = envir.at(key);

		// 1) NdMSpaceSpec の場合 → NdM を生成
		if(obj.isKindOf(NdMSpaceSpec)) {
			specObj = obj;

			// 既存値が NdM で、かつ再生していなければ free してから再生成する。
			// （stopTag などで完全にミュートされた後の再定義ケース）
			// if(oldVal.isKindOf(NdM)) {
			// 	oldIsPlaying = oldVal.isPlaying;
			// 	if(oldIsPlaying.not) {
			// 		oldVal.free;
			// 		oldVal = nil;
			// 	};
			// };

			// outBus が明示されていればそれを優先
			if(specObj.outBus.notNil) {
				outBus = specObj.outBus;
			} {
				// なければ既存 NdM の outbus、さらに無ければ 0
				if(oldVal.isKindOf(NdM)) {
					outBus = oldVal.out;
				} {
					outBus = 0;
				};
			};

			ndmObj = NdM(key, specObj.func, outBus);

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

		// 2) 素の Function は NdM にせず、そのまま Function として保存
		if(obj.isKindOf(Function)) {
			// 既存値が NdM なら free しておく（音止め）
			if(oldVal.isKindOf(NdM)) {
				oldVal.free;
			};

			envir.put(key, obj);
			^obj;
		};

		// 3) その他（String 等）は、そのまま保存
		//    既存値が NdM なら free だけしてから上書き
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

	// NdM 生成後に呼び出され、バッファされたタグを適用する
	applyTagsTo { |ndmInstance|
		if(tagBuffer.notNil) {
			tagBuffer.do { |tagItem|
				ndmInstance.tag(tagItem);
			};
		};
	}
}


// Function → NdMSpaceSpec front-end

+ Function {
	nd {
		^NdMSpaceSpec.fromFunction(this);
	}
}
