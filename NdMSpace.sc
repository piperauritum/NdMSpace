///
/// Adopted Policy (Provisional Rules: dirty / restore handling)
///
/// This file treats graph rebuilding (rebuild) and state restoration (restore)
/// in NdM based on the following policy.
///
/// ------------------------------------------------------------
/// Terminology mapping
/// ------------------------------------------------------------
///
/// - "Mark" (dirty)
///   -> `graphDirty` / `graphDirtyValue`
///   A state indicating that the guarantee of correct execution order
///   has been lost due to changes in node connections (writer -> reader)
///   or output destinations.
///
/// - "Marking as dirty"
///   -> Setting `graphDirty = true` via `markGraphDirty`, etc.
///
/// - "Clearing the mark"
///   -> Setting `graphDirty = false` via `clearGraphDirty`.
///   This must be done **only after the rebuild process has fully completed**.
///
/// - "Rebuilding execution order"
///   -> `rebuildGraphIfDirty`
///   A process that recalculates the correct execution order from recorded
///   connection information (writers / readers) and restarts necessary nodes.
///
/// - "Restoring output destination information (restore)"
///   -> `restoreFromNode`, `restoreGraphFromOutbus`, etc.
///   A process that re-applies, on the language side, which bus / outTarget
///   each node should currently write to, immediately before execution.
///
/// ------------------------------------------------------------
/// Basic policy
/// ------------------------------------------------------------
///
/// 1. Any operation that changes connection relationships or output destinations
///    must mark the graph as dirty.
///    Examples:
///    - Creating a new node
///    - Adding or removing writer / reader relationships
///    - Changing outbus / outTarget
///
/// 2. While the dirty mark is set, the system is considered to be in a state
///    where the correct execution order is not guaranteed.
///    Playback may still occur, but the system must ensure that it eventually
///    converges to the correct order.
///
/// 3. Rebuilding the execution order is performed only when the dirty mark is set.
///    If `graphDirty == false`, no rebuild is performed.
///
/// 4. The dirty mark must be cleared only after the rebuild process has completed
///    in its entirety.
///    This includes:
///    - Building the kick plan
///    - Restarting all required nodes
///    - Completing the full sequence of operations, including asynchronous
///      processing such as fork / sched
///
/// 5. Restoring output destination information (restore) is consolidated and
///    performed immediately before playback.
///    - Restore is not performed at the moment the dirty mark is set
///    - Restore is not performed during rebuild
///    - Output destination information is finalized only just before execution
///
/// 6. If the execution order cannot be determined (due to cycles, missing links,
///    or similar issues), the situation must not be treated as a success.
///    - The dirty mark must not be cleared
///    - The system must avoid appearing as if recovery succeeded when it did not
///
/// ------------------------------------------------------------
/// Purpose of this policy
/// ------------------------------------------------------------
///
/// - To make the timing of marking and clearing dirty explicit and unambiguous,
///   making state transitions easier to track.
/// - To separate the responsibilities of restore and rebuild, preventing
///   premature restoration or mid-process finalization.
/// - To prevent situations where the result appears correct by accident while
///   the internal state remains inconsistent.
///
/// This policy is provisional and may be revised based on actual usage
/// and verification results.
///
/// ============================================================
///
/// ### 採用する考え方（暫定ルール：dirty / restore 運用）
///
/// 本ファイルでは、NdM における **graph の再構築（rebuild）** と **状態復元（restore）** を、次の考え方に基づいて扱う。
///
/// #### 用語対応
///
/// * **印（dirty）**
/// → `graphDirty` / `graphDirtyValue`
/// ノード間の接続関係（writer → reader）や出力先が変わり、
/// 「現在の実行順が正しい保証が失われた」ことを示す状態。
///
/// * **印を付ける**
/// → `markGraphDirty` 等により `graphDirty = true` にすること。
///
/// * **印を外す**
/// → `clearGraphDirty` により `graphDirty = false` にすること。
/// これは **再構築が最後まで完了した後** にのみ行う。
///
/// * **順番を整え直す処理**
/// → `rebuildGraphIfDirty`
/// 記録済みの接続情報（writers / readers）から、
/// 正しい実行順を再計算し、必要なノードを再起動する処理。
///
/// * **出力先の情報を登録し直す処理（restore）**
/// → `restoreFromNode` / `restoreGraphFromOutbus` 等
/// 各ノードが「現在どの bus / outTarget に書き出すべきか」を
/// 実行前に言語側から再設定する処理。
///
/// #### 基本方針
///
/// 1. **接続関係や出力先が変わる操作では、必ず印（dirty）を付ける。**
/// 例：
///
/// * ノードの新規作成
/// * writer / reader 関係の追加・削除
/// * outbus / outTarget の変更
///
/// 2. **印（dirty）が付いている間は、「正しい順番が保証されていない」状態として扱う。**
/// この状態では、再生が行われても「最終的に正しい順番になること」を保証する必要がある。
///
/// 3. **順番を整え直す処理（rebuild）は、印が付いている場合にのみ行う。**
/// `graphDirty == false` の場合は何もしない。
///
/// 4. **印を外すのは、順番を整え直す処理がすべて完了した後のみとする。**
///
/// * kick 計画の作成
/// * 必要なノードの再起動
/// * 非同期処理（fork / sched）を含む一連の処理
/// これらが **最後まで終わったことが確認できた時点** で、初めて印を外す。
///
/// 5. **出力先の情報を登録し直す処理（restore）は、「再生直前」にまとめて行う。**
///
/// * dirty を付けた時点では restore を行わない
/// * rebuild の途中でも restore を行わない
/// * 実際に再生を行う直前で、現在の outbus / outTarget 情報を確定させる
///
/// 6. **順番が決められない場合（循環・欠落など）は、成功扱いにしない。**
///
/// * 印は外さない
/// * 失敗した状態が「正常に復旧したように見える」ことを避ける
///
/// #### このルールの目的
///
/// * 「いつ印が付くのか」「いつ印が外れるのか」を一意にし、
/// 状態遷移を追いやすくするため。
/// * restore と rebuild の責務を分離し、
/// 「早すぎる復元」「途中での確定」を防ぐため。
/// * 実行結果がたまたま正しく見えるケースでも、
/// 内部状態が壊れたまま進行することを防ぐため。
///
/// 本ルールは暫定であり、実運用・検証の結果に応じて修正される。
///


/* ============================================================
NdMSpace — ProxySpace-like front-end for NdM
============================================================ */

NdMSpace : LazyEnvir {

	classvar <current;

	var server;
	var <>spaceGroup;

	// New: carry last requested dbg value for NdM propagation (normalized to 0/1), or nil if unspecified.
	var <>lastDbgValue;

	// New: NdMSpace-owned [DBG] switch (normalized to 0/1).
	// This is for NdMSpace-level logs (e.g. [DBG][GRAPH]/[DBG][SPACE]).
	var <>spaceDbgValue;

	// New: coalesce dbg propagation into one tick (apply to existing NdM instances).
	var dbgApplyPending;
	var dbgApplyValue;
	var dbgApplyScheduled;

	// Task3/4: coalesce graph rebuild requests into one tick.
	var graphApplyPending;
	var graphApplyRunning;
	var graphApplyRerun;

	// TEST HOOK (T2 only): extend visible running window by sched/yield (default 0.0).
	var <>graphApplyTestHoldSec;

	// --------------------------------------------------------------------
	// TRACE (provisional): execution path capture as an ordered list.
	//
	// SPEC:
	// - traceList is an Array (order is the only source of truth).
	// - each row is a Dictionary with fixed keys (seq/traceId/threadId/tag/key/where/reqGen/auxState/auxValue/auxReason).
	// - Array.add returns a new Array, so traceList must be reassigned.
	//
	// WHERE VOCABULARY (fixed):
	// - \user                : invoked directly by user code
	// - \spec_play_mark      : NdMSpaceSpec.play marked autoPlay
	// - \spec_play_setFunc   : setFunc applied in spec.play context
	// - \putFromSpec         : during putFromSpec execution
	// - \putFromSpec_autoPlay: autoPlay consumption point
	// - \dbg_scan            : during NdMSpace.dbg scan
	// - \dbg_delta           : triggered by NdM.dbg old/new delta
	//
	// Future class API (NdMTrace) - must match current helper semantics:
	// - reset()
	//     - clears traceList and counters (traceSeq/traceIdNext/traceActiveId)
	// - begin(where, key=\none, reqGen=-1, auxState=\none, auxValue=-1, auxReason=\none) -> traceId
	//     - starts an execution unit and returns traceId (maps to traceBegin)
	// - push(tag, key=\none, where=\none, reqGen=-1, auxState=\none, auxValue=-1, auxReason=\none, traceId=nil)
	//     - appends one checkpoint row (maps to tracePush)
	// - end(traceId=nil, where=\none, key=\none, reqGen=-1, auxState=\none, auxValue=-1, auxReason=\none)
	//     - ends an execution unit (maps to traceEnd)
	// - dump(traceId=nil)
	//     - human-readable dump (all rows or rows filtered by traceId)
	// - slice(traceId)
	//     - returns rows (Array) filtered by traceId for comparison
	//
	// Note:
	// - This is intentionally implemented as NdMSpace fields + helpers (minimal).
	// - Future work: split into a dedicated class (e.g. NdMTrace) once checkpoints stabilize.

	// New: NdMTrace instance (Phase B). Wrapper methods will delegate in later phases.
	var traceObj;

	// Toggle: whether NdMSpace uses NdMTrace (delegate target) or legacy fields only.
	// - Provide both getter and setter (traceUseObj / traceUseObj_).
	var <>traceUseObj;

	// Default out-bus (used only when specObj.outBus is nil and oldVal is not an NdM).
	// Types: nil / Integer / Array(Integer) / Bus
	var defaultOutValue;


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
	// Policy B: normalize clean => exitAll + freeAll + reset.
	*clean {
		this.exitAll;
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
	// a.dbg(1);     // store dbg flag = true
	// a.dbg(0);     // store dbg flag = false
	// a.dbg;        // -> Boolean (default: false when nil)
	dbg { |value|
		var flag;
		var xres;

		var traceId = this.traceBegin(\dbg, \none, -1, \none, -1, \none);
		this.tracePush('space.dbg.enter', \none, \dbg, -1, ("value:" ++ value.asString).asSymbol, -1, \none, traceId);
		this.traceEnd(traceId, \dbg, \none, -1, \none, -1, \none);

		xres = this;

		// getter: return space log flag (default: false when nil)
		if(value.isNil) {
			xres = (spaceDbgValue ? false);
		} {
			// setter: same normalization rule as NdM.dbg
			if(value.isNumber) {
				flag = (value > 0);
			} {
				flag = (value == true);
			};

			// 1) store for NdM propagation
			lastDbgValue = flag;

			// 2) store for NdMSpace-owned logs
			spaceDbgValue = flag;

			// 3) defer propagation to existing NdM instances (coalesce to next tick)
			dbgApplyPending = true;
			dbgApplyValue = flag;
			this.scheduleApplyDbg;

			xres = this;
		};

		^xres;
	}

	dbg_ { |value|
		var flag;

		var traceId = this.traceBegin(\dbg_, \none, -1, \none, -1, \none);
		this.tracePush('space.dbg_.enter', \none, \dbg_, -1, ("value:" ++ value.asString).asSymbol, -1, \none, traceId);
		this.traceEnd(traceId, \dbg_, \none, -1, \none, -1, \none);

		// setter: same normalization rule as NdM.dbg
		if(value.isNumber) {
			flag = (value > 0);
		} {
			flag = (value == true);
		};

		// 1) store for NdM propagation
		lastDbgValue = flag;

		// 2) store for NdMSpace-owned logs
		spaceDbgValue = flag;

		// 3) defer propagation to existing NdM instances (coalesce to next tick)
		dbgApplyPending = true;
		dbgApplyValue = flag;
		this.scheduleApplyDbg;

		^this;
	}

	// New: apply pending dbg propagation to existing NdM instances (scheduled by dbg setter).
	applyDbgPending {
		var table;
		var flag;

		if(dbgApplyPending.not) {
			dbgApplyScheduled = false;
			^this;
		};

		// keep the latest flag (Boolean)
		flag = dbgApplyValue;

		table = NdM.instances;
		if(table.notNil) {
			table.values.do { |ndmInstance|
				if(ndmInstance.notNil) {
					ndmInstance.dbg(flag);
				};
			};
		};

		// close pending state
		dbgApplyPending = false;
		dbgApplyValue = nil;
		dbgApplyScheduled = false;

		^this;
	}

	// New: schedule dbg propagation once on the next tick (prevent multi-sched).
	// NOTE: expects applyDbgPending to exist (step 3).
	scheduleApplyDbg {
		var space;
		var doSchedule;

		space = this;
		doSchedule = false;

		if((dbgApplyPending == true) && (dbgApplyScheduled == false)) {
			dbgApplyScheduled = true;
			doSchedule = true;
		};

		if(doSchedule == true) {
			// SystemClock.sched(0.0, {
			if(space.notNil) {
				space.applyDbgPending;
			};
			// nil;
			// });
		};

		^this;
	}

	debugPost { |msgFunc|
		var flag;
		var msg;

		flag = this.dbg;

		if(flag == true) {
			if(msgFunc.isKindOf(Function)) {
				msg = msgFunc.value;
			} {
				msg = msgFunc;
			};

			if(msg.notNil) {
				msg.postln;
			};
		};

		^this;
	}

	// --------------------------------------------------------------------
	// TRACE (provisional): NdMSpace fields + helpers (minimal implementation).
	// Future: split into a dedicated class (e.g. NdMTrace) once checkpoints stabilize.
	// --------------------------------------------------------------------

	traceReset {
		var useObj;

		useObj = traceUseObj ? true;

		if(useObj == true) {
			if(traceObj.isNil) {
				traceObj = NdMTrace.new;
			};
			traceObj.reset;
		} {
			traceObj = nil;
		};

		^this;
	}

	traceBegin { |whereIn, keyIn, reqGenIn, auxStateIn, auxValueIn, auxReasonIn|
		var where;
		var key;
		var reqGen;
		var auxState;
		var auxValue;
		var auxReason;
		var traceId;
		var useObj;

		where = whereIn ? \none;
		key = keyIn ? \none;
		reqGen = reqGenIn ? -1;
		auxState = auxStateIn ? \none;
		auxValue = auxValueIn ? -1;
		auxReason = auxReasonIn ? \none;

		useObj = traceUseObj ? true;

		if(useObj == true) {
			if(traceObj.isNil) {
				traceObj = NdMTrace.new;
				traceObj.reset;
			};
			traceId = traceObj.begin(where, key, reqGen, auxState, auxValue, auxReason);
		} {
			traceId = -1;
		};

		^traceId;
	}

	traceEnd { |traceIdIn, whereIn, keyIn, reqGenIn, auxStateIn, auxValueIn, auxReasonIn|
		var where;
		var key;
		var reqGen;
		var auxState;
		var auxValue;
		var auxReason;
		var useObj;

		where = whereIn ? \none;
		key = keyIn ? \none;
		reqGen = reqGenIn ? -1;
		auxState = auxStateIn ? \none;
		auxValue = auxValueIn ? -1;
		auxReason = auxReasonIn ? \none;

		useObj = traceUseObj ? true;

		if(useObj == true) {
			if(traceObj.isNil) {
				traceObj = NdMTrace.new;
				traceObj.reset;
			};
			traceObj.end(traceIdIn, where, key, reqGen, auxState, auxValue, auxReason);
		};

		^this;
	}

	tracePush { |tagIn, keyIn, whereIn, reqGenIn, auxStateIn, auxValueIn, auxReasonIn, traceIdIn|
		var tag;
		var key;
		var where;
		var reqGen;

		var auxState;
		var auxValue;
		var auxReason;

		var useObj;

		tag = tagIn ? \none;
		key = keyIn ? \none;
		where = whereIn ? \none;
		reqGen = reqGenIn ? -1;

		auxState = auxStateIn ? \none;
		auxValue = auxValueIn ? -1;
		auxReason = auxReasonIn ? \none;

		useObj = traceUseObj ? true;

		if(useObj == true) {
			if(traceObj.isNil) {
				traceObj = NdMTrace.new;
				traceObj.reset;
			};
			traceObj.push(tag, key, where, reqGen, auxState, auxValue, auxReason, traceIdIn);
		};

		^this;
	}

	traceSlice { |traceIdIn|
		var rows;
		var useObj;

		rows = Array.new;
		useObj = traceUseObj ? true;

		if((useObj == true) && traceObj.notNil) {
			rows = traceObj.slice(traceIdIn);
		} {
			rows = Array.new;
		};

		^rows;
	}

	traceDump { |traceIdIn|
		var rows;
		var useObj;

		rows = Array.new;
		useObj = traceUseObj ? true;

		if((useObj == true) && traceObj.notNil) {
			rows = traceObj.dump(traceIdIn);
		};

		rows;
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
		var grp;

		space = current;

		grp = nil;
		if(space.notNil) {
			grp = space.spaceGroup;
			space.pop;
		};

		if(grp.notNil) {
			space.debugPost {
				"[DBG][SPACE] free spaceGroup nodeID=" ++ grp.nodeID.asString
			};
		};

		// Free NdMSpace-owned group (created in initForServer).
		if(grp.notNil) {
			try { grp.free; } { };
			space.spaceGroup = nil;
		};

		// If NdMSpace was nested, pop returns to the outer NdMSpace.
		// Restore classvar current accordingly; otherwise, set nil.
		if(currentEnvironment.isKindOf(NdMSpace)) {
			current = currentEnvironment;
		} {
			current = nil;
		};

		"===== NdMSpace.exit =====".postln;
		"NdMSpace cleared and environment restored.".postln;
		"=========================".postln;

		^space;
	}

	*exitAll {
		var space;
		var didExit;

		didExit = false;
		space = current;

		while { space.notNil } {
			this.exit;
			didExit = true;
			space = current;
		};

		^didExit;
	}

	initForServer { |srv|
		var oldGrp;

		server = srv;
		lastDbgValue = nil;
		spaceDbgValue = nil;

		dbgApplyPending = false;
		dbgApplyValue = nil;
		dbgApplyScheduled = false;

		graphApplyPending = false;
		graphApplyRunning = false;
		graphApplyRerun = false;
		graphApplyTestHoldSec = 0.0;

		// TRACE: delegate target (NdMTrace) only. Legacy trace fields are removed.
		// Toggle default: ON (use NdMTrace).
		traceUseObj = true;

		// Phase B: prepare delegate target (NdMTrace).
		if(traceUseObj == true) {
			traceObj = NdMTrace.new;
			traceObj.reset;
		} {
			traceObj = nil;
		};

		oldGrp = spaceGroup;
		if(oldGrp.notNil) {
			try { oldGrp.free; } { };
		};

		// NdMSpace-owned container group under default group (usually Group 1).
		// All NdM proxy groups will be moved under this group for deterministic ordering.
		spaceGroup = Group.head(server);
	}

	graphMonitor {
		^NdMNameSpace.instance;
	}

	graphRebuildOrderInfo { |activeKeysIn|
		var monitor;
		var res;
		var activeKeys;

		this.tracePush(
			'graphRebuildOrderInfo.enter',
			\none,
			\graphRebuildOrderInfo,
			-1,
			("activeKeysIn.nil:" ++ activeKeysIn.isNil.asString).asSymbol,
			-1,
			\none,
			nil
		);

		monitor = this.graphMonitor;
		if(monitor.isNil) {
			^IdentityDictionary[
				(\order -> nil),
				(\hasFeedbackLoop -> false),
				(\feedbackMissing -> Array.new)
			];
		};

		activeKeys = if(activeKeysIn.isNil) {
			monitor.activeKeys
		} {
			activeKeysIn
		};

		res = monitor.computeRebuildOrder(activeKeys);
		^res;
	}

	rebuildGraphIfDirty {
		var monitor;
		var order;
		var activeKeys;
		var res;
		var dbgOrder;
		var kickKeys;
		var kickNdms;
		var delayEnd;

		monitor = NdMNameSpace.instance;

		if(monitor.isNil) {
			^nil;
		};

		if(monitor.graphDirtyValue.not) {
			^nil;
		};

		activeKeys = this.graphRebuildCollectActiveKeys(envir);

		// DBG: NdMSpace.dbg (spaceDbgValue) controls space-level [DBG].
		dbgOrder = this.dbg;

		res = this.graphRebuildComputeOrderWithLogs(monitor, activeKeys, dbgOrder);
		order = this.graphRebuildResolveOrderOrAbort(monitor, res);
		if(order.isNil) {
			^nil;
		};

		# kickKeys, kickNdms = this.graphRebuildMakeKickPlan(order, envir, dbgOrder);

		delayEnd = this.graphRebuildScheduleKicks(kickNdms, kickKeys, dbgOrder);
		this.graphRebuildScheduleObserveAfterKicks(delayEnd, order, kickNdms, spaceGroup, dbgOrder);

		// Safety: also try immediate reorder (best-effort) based on topoOrder.
		// (The definitive reorder is done in observe-after-kicks.)
		this.graphReorderProxyGroupsToTopoOrder(order);

		// Fix: dirty clear is done in graphRebuildScheduleObserveAfterKicks (async tail),
		// after proxy group reordering is applied on the server.

		^order;
	}

	graphRebuildCollectActiveKeys { |envirLocal|
		var activeKeys;
		activeKeys = Array.new;

		this.tracePush(
			'graphRebuildCollectActiveKeys.enter',
			\none,
			\graphRebuildCollectActiveKeys,
			-1,
			("envir.nil:" ++ envirLocal.isNil.asString).asSymbol,
			-1,
			\none,
			nil
		);

		envirLocal.keysValuesDo { |kk, vv|
			if(vv.isKindOf(NdM)) {
				activeKeys = activeKeys.add(kk);
			};
		};

		^activeKeys;
	}

	graphRebuildComputeOrderWithLogs { |monitor, activeKeys, dbgOrder|
		var res;
		var activeStr;
		var orderStr;

		this.tracePush(
			'graphRebuildComputeOrderWithLogs.enter',
			\none,
			\graphRebuildComputeOrderWithLogs,
			-1,
			("monitor.nil:" ++ monitor.isNil.asString
				++ " activeKeys.size:" ++ activeKeys.size.asString
				++ " dbgOrder:" ++ dbgOrder.asString).asSymbol,
			-1,
			\none,
			nil
		);

		res = monitor.computeRebuildOrder(activeKeys);

		if(dbgOrder) {
			activeStr = activeKeys.asArray.sort { |a, b| (a.asString < b.asString) }.asString;
			this.debugPost { "[DBG][GRAPH] dirty=true activeKeys=" ++ activeStr };

			if(res.isKindOf(Dictionary)) {
				orderStr = res[\order].asString;
				this.debugPost { "[DBG][GRAPH] computeRebuildOrder => dict order=" ++ orderStr };
			} {
				orderStr = res.asString;
				this.debugPost { "[DBG][GRAPH] computeRebuildOrder => order=" ++ orderStr };
			};
		};

		^res;
	}

	graphRebuildResolveOrderOrAbort { |monitor, res|
		var order;
		var feedbackMissing;
		var feedbackSig;
		var prevSig;
		var sigSame;
		var sortedMissing;

		this.tracePush(
			'graphRebuildResolveOrderOrAbort.enter',
			\none,
			\graphRebuildResolveOrderOrAbort,
			-1,
			("monitor.nil:" ++ monitor.isNil.asString
				++ " res.class:" ++ res.class.asString).asSymbol,
			-1,
			\none,
			nil
		);

		order = nil;

		if(res.isKindOf(Dictionary)) {
			order = res[\order];

			// Policy: feedback loop => order is nil (computed by monitor).
			if(order.isNil) {
				feedbackMissing = res[\feedbackMissing];

				sortedMissing = feedbackMissing.asArray.sort { |a, b| (a.asString < b.asString) };
				feedbackSig = sortedMissing.asString;

				prevSig = monitor.feedbackWarnSig;
				sigSame = prevSig.notNil && (prevSig == feedbackSig);

				if(sigSame.not) {
					("[WARN] NdMSpace.rebuildGraphIfDirty aborted due to feedback loop: missing=" ++ sortedMissing.asString).postln;
					monitor.feedbackWarnSig = feedbackSig;
				};

				^nil;
			};
		} {
			order = res;
		};

		^order;
	}

	graphRebuildMakeKickPlan { |order, envirLocal, dbgOrder|
		var k;
		var ndmObj;
		var proxyObj;
		var nodeIdStr;
		var nodeIdVal;
		var kickKeys;
		var kickNdms;

		var isPlayFlag;
		var skipReason;

		var proxyClsStr;
		var hasNodeId;
		var hasIsPlaying;
		var isPlayStr;

		var snap;
		var runVal;

		this.tracePush(
			'graphRebuildMakeKickPlan.enter',
			\none,
			\graphRebuildMakeKickPlan,
			-1,
			("order.size:" ++ order.size.asString
				++ " dbgOrder:" ++ dbgOrder.asString).asSymbol,
			-1,
			\none,
			nil
		);

		this.debugPost { "[DBG][GRAPH] makeKickPlan begin" };

		kickKeys = Array.new;
		kickNdms = Array.new;

		if(dbgOrder) {
			this.debugPost { "[DBG][GRAPH] topoOrder=" ++ order.asString };
		};

		order.do { |kk|
			k = kk;

			ndmObj = envirLocal.at(k);
			if(ndmObj.isKindOf(NdM)) {
				proxyObj = ndmObj.proxy;

				isPlayFlag = false;
				skipReason = "unknown";

				proxyClsStr = "nil";
				hasNodeId = false;
				hasIsPlaying = false;
				isPlayStr = "nil";

				if(proxyObj.isNil) {
					skipReason = "proxyNil";
				} {
					proxyClsStr = proxyObj.class.asString;

					hasIsPlaying = proxyObj.respondsTo(\isPlaying);
					if(hasIsPlaying) {
						isPlayFlag = proxyObj.isPlaying;
						isPlayStr = isPlayFlag.asString;
					} {
						isPlayFlag = false;
						isPlayStr = "noMethod";
					};

					if(isPlayFlag) {
						skipReason = "playing";
					} {
						skipReason = "notPlaying";
					};
				};

				// optional: nodeID if available (also for skip log)
				nodeIdStr = "";
				nodeIdVal = nil;
				if(proxyObj.notNil) {
					hasNodeId = proxyObj.respondsTo(\nodeID);

					if(hasNodeId) {
						try {
							nodeIdVal = proxyObj.nodeID;
						} {
							nodeIdVal = nil;
						};
					};

					this.tracePush(
						'proxy.read.makeKickPlan',
						k,
						\graphRebuildMakeKickPlan,
						-1,
						("proxy.class:" ++ proxyClsStr
							++ " isPlaying:" ++ isPlayStr
							++ " nodeID:" ++ nodeIdVal.asString
							++ " snap.running:" ++ runVal.asString).asSymbol,
						-1,
						'reason:makeKickPlan.read',
						nil
					);
				};
				if(nodeIdVal.notNil) {
					nodeIdStr = (" nodeID=" ++ nodeIdVal.asString);
				} {
					if(hasNodeId) {
						nodeIdStr = " nodeID=nil";
					} {
						nodeIdStr = " nodeID=noMethod";
					};
				};

				// kick-check log (before if(isPlayFlag))
				runVal = false;

				if(dbgOrder) {
					var reqVal;
					var wantVal;
					var playVal;
					var nodeVal;

					snap = nil;

					reqVal = "nil";
					wantVal = "nil";
					playVal = "nil";
					nodeVal = "nil";

					try {
						snap = ndmObj.kickSnapshot;
					} {
						snap = nil;
					};

					if(snap.notNil) {
						runVal = snap.at(\running);
						reqVal = snap.at(\reqGen);
						wantVal = snap.at(\wantPlay);
						playVal = snap.at(\isPlaying);
						nodeVal = snap.at(\nodeID);
					};

					// normalize runVal to Boolean
					if(runVal.isNumber) {
						runVal = (runVal > 0);
					} {
						runVal = (runVal == true);
					};


					("[DBG][GRAPH] kick(check) [" ++ k.asString ++ "] "
						++ "snapId=" ++ snap.identityHash.asString
						++ " running=" ++ runVal.asString
						++ " reqGen=" ++ reqVal.asString
						++ " wantPlay=" ++ wantVal.asString
						++ " isPlaying=" ++ playVal.asString
						++ " nodeID=" ++ nodeVal.asString
					).postln;


					if(snap.notNil) {
						this.debugPost {
							"[DBG][GRAPH] kick(snapKeys) [" ++ k.asString ++ "]"
							++ " keys=" ++ snap.keys.asString
						};

						snap.keysValuesDo { |kk, vv|
							this.debugPost {
								"[DBG][GRAPH] kick(snapKV) [" ++ k.asString ++ "]"
								++ " " ++ kk.asString
								++ "=" ++ vv.asString
								++ " class=" ++ vv.class.asString
							};
						};
					} {
						this.debugPost {
							"[DBG][GRAPH] kick(snapKeys) [" ++ k.asString ++ "] snap=nil"
						};
					};

					// NEW: evaluate actual plan condition (proxy.isPlaying || snap.running)
					this.debugPost {
						"[DBG][GRAPH] kick(eval) [" ++ k.asString ++ "]"
						++ " proxy.isPlaying=" ++ isPlayFlag.asString
						++ " snap.running=" ++ runVal.asString
						++ " => plan=" ++ ((isPlayFlag || runVal)).asString
					};
				};

				if(isPlayFlag || runVal) {

					// record actual kick order
					kickKeys = kickKeys.add(k);
					kickNdms = kickNdms.add(ndmObj);

					this.debugPost { "[DBG][GRAPH] kick(plan) [" ++ k.asString ++ "]" ++ nodeIdStr };

				} {
					if(dbgOrder) {
						this.debugPost {
							"[DBG][GRAPH] kick(skip) [" ++ k.asString ++ "]"
							++ " reason=" ++ skipReason
							++ " proxy.class=" ++ proxyClsStr
							++ " isPlaying=" ++ isPlayStr
							++ nodeIdStr
						};
					};
				};
			};
		};

		this.debugPost { "[DBG][GRAPH] makeKickPlan end" };

		^ [ kickKeys, kickNdms ];
	}

	graphRebuildScheduleKicks { |kickNdms, kickKeys, dbgOrder|
		var kickStr;
		var delayEnd;
		var space;

		this.tracePush(
			'graphRebuildScheduleKicks.enter',
			\none,
			\graphRebuildScheduleKicks,
			-1,
			("kickKeys.size:" ++ kickKeys.size.asString
				++ " kickNdms.size:" ++ kickNdms.size.asString
				++ " dbgOrder:" ++ dbgOrder.asString).asSymbol,
			-1,
			\none,
			nil
		);

		space = this;
		delayEnd = 0.0;

		kickNdms.do { |nd, ii|
			var delaySec;
			var keyLocal;
			var proxyLocal;
			var nodeBefore;
			var nodeAfter;
			var grpBefore;
			var grpAfter;
			var grpIdBefore;
			var grpIdAfter;

			delaySec = (ii.asFloat * 0.06);
			keyLocal = nd.key;

			proxyLocal = nil;
			nodeBefore = nil;
			nodeAfter = nil;
			grpBefore = nil;
			grpAfter = nil;
			grpIdBefore = nil;
			grpIdAfter = nil;

			try {
				proxyLocal = nd.proxy;
			} {
				proxyLocal = nil;
			};

			if(proxyLocal.notNil) {
				try {
					nodeBefore = proxyLocal.nodeID;
				} {
					nodeBefore = nil;
				};

				try {
					grpBefore = proxyLocal.group;
				} {
					grpBefore = nil;
				};

				if(grpBefore.notNil) {
					try {
						grpIdBefore = grpBefore.nodeID;
					} {
						grpIdBefore = nil;
					};
				};
			};

			space.debugPost { "[DBG][GRAPH][KICK] sched key=" ++ keyLocal.asString
				++ " ii=" ++ ii.asString
				++ " delay=" ++ delaySec.asString
				++ " nodeID(before)=" ++ nodeBefore.asString
				++ " groupID(before)=" ++ grpIdBefore.asString
			};

			SystemClock.sched(delaySec, {
				space.debugPost { "[DBG][GRAPH][KICK] fire  key=" ++ keyLocal.asString
					++ " ii=" ++ ii.asString
				};

				try { nd.requestGraphRebuild; } {
					space.debugPost { "[WARN][GRAPH][KICK] requestGraphRebuild failed key=" ++ keyLocal.asString };
				};

				if(proxyLocal.notNil) {
					try {
						nodeAfter = proxyLocal.nodeID;
					} {
						nodeAfter = nil;
					};

					try {
						grpAfter = proxyLocal.group;
					} {
						grpAfter = nil;
					};

					if(grpAfter.notNil) {
						try {
							grpIdAfter = grpAfter.nodeID;
						} {
							grpIdAfter = nil;
						};
					};

					space.debugPost { "[DBG][GRAPH][KICK] after key=" ++ keyLocal.asString
						++ " nodeID(after)=" ++ nodeAfter.asString
						++ " groupID(after)=" ++ grpIdAfter.asString
					};
				};

				nil
			});
		};

		// NOTE: delayEnd must be computed regardless of dbgOrder,
		// because observe-after-kicks must run after kicks for audio correctness.
		delayEnd = ((kickNdms.size.asFloat * 0.06) + 0.02);

		if(dbgOrder) {
			kickStr = kickKeys.asString;
			space.debugPost { "[DBG][GRAPH] kickedKeys=" ++ kickStr };
		};

		^delayEnd;
	}

	graphRebuildScheduleObserveAfterKicks { |delayEnd, order, kickNdms, spaceGroup, dbgOrder|
		var monitor;
		var grpSet;
		var grpArr;
		var keyLocal;
		var nodeLocal;
		var grpObj;
		var grpIdVal;
		var proxyObj;
		var space;

		var traceId;
		var auxState;
		var auxValue;

		traceId = this.traceBegin(\graphRebuildScheduleObserveAfterKicks, \none, -1, \none, -1, \none);
		this.tracePush(
			'graph.observeAfterKicks.enter',
			\none,
			\graphRebuildScheduleObserveAfterKicks,
			-1,
			("delayEnd:" ++ delayEnd.asString).asSymbol,
			-1,
			\none,
			traceId
		);

		space = this;
		monitor = NdMNameSpace.instance;

		traceId = this.traceBegin(\graphRebuildScheduleObserveAfterKicks, \none, -1, \none, -1, \none);

		SystemClock.sched(delayEnd, {
			var reorderOk;

			reorderOk = true;

			if(dbgOrder) {
				space.debugPost { "[DBG][GRAPH][ORDER] observe-after-kicks delay=" ++ delayEnd.asString };
				space.debugPost { "[DBG][GRAPH][ORDER] topoOrder=" ++ order.asString };
			};

			// Policy 2: reorder NdM proxy groups to match topoOrder.
			// This changes actual server execution order (writer groups before reader groups).
			// NOTE: must run regardless of dbgOrder (audio correctness must not depend on dbg).
			if(spaceGroup.notNil) {
				order.do { |kk2|
					var nd2;
					var px2;
					var gg2;

					nd2 = envir.at(kk2);
					px2 = nil;
					gg2 = nil;

					if(nd2.isKindOf(NdM)) {
						try { px2 = nd2.proxy; } { px2 = nil; };
						if(px2.notNil) {
							try { gg2 = px2.group; } { gg2 = nil; };
							if(gg2.notNil) {
								try { gg2.moveToTail(spaceGroup); } { reorderOk = false; };
							};
						};
					};
				};
			};

			if(dbgOrder) {
				grpSet = IdentitySet.new;

				kickNdms.do { |nd|
					keyLocal = nd.key;
					nodeLocal = nil;
					grpObj = nil;
					grpIdVal = nil;

					try { proxyObj = nd.proxy; } { proxyObj = nil; };

					if(proxyObj.notNil) {
						try { nodeLocal = proxyObj.nodeID; } { nodeLocal = nil; };
						try { grpObj = proxyObj.group; } { grpObj = nil; };

						if(grpObj.notNil) {
							try { grpIdVal = grpObj.nodeID; } { grpIdVal = nil; };
							grpSet.add(grpObj);
						};
					};

					space.debugPost { "[DBG][GRAPH][ORDER] key=" ++ keyLocal.asString
						++ " nodeID=" ++ nodeLocal.asString
						++ " groupID=" ++ grpIdVal.asString
					};
				};

				grpArr = grpSet.asArray;
				grpArr.do { |gg|
					grpIdVal = nil;
					try { grpIdVal = gg.nodeID; } { grpIdVal = nil; };

					space.debugPost { "[DBG][GRAPH][ORDER] queryTree groupID=" ++ grpIdVal.asString };
					gg.queryTree(true);
				};
			};

			// Clear dirty only after reordering is applied.
			// If reorder failed, keep dirty and force rerun.
			if(monitor.notNil) {
				monitor.feedbackWarnSig = nil;

				if(reorderOk) {
					monitor.clearGraphDirty;

					if(dbgOrder) {
						space.debugPost { "[DBG][GRAPH] clearGraphDirty done" };
					};
				} {
					graphApplyRerun = true;
					space.debugPost { "[WARN][GRAPH] reorder failed; keep dirty and rerun" };
				};
			};

			// Release the "running" guard here (async tail reached).
			graphApplyRunning = false;

			auxState = ("pending:" ++ graphApplyPending.asString
				++ " running:" ++ graphApplyRunning.asString
				++ " rerun:" ++ graphApplyRerun.asString).asSymbol;
			auxValue = this.identityHash;

			this.tracePush('graph.run.endTail', \none, \graphRebuildScheduleObserveAfterKicks, -1, auxState, auxValue, 'reason:endTail', traceId);

			// If requests arrived during running, fold them into one rerun.
			if(graphApplyRerun) {
				graphApplyRerun = false;
				this.requestGraphRebuildFrom(\graphRebuildScheduleObserveAfterKicks);
			};

			space.traceEnd(traceId, \graphRebuildScheduleObserveAfterKicks, \none, -1, \none, -1, \none);

			nil
		});

		^nil;
	}

	graphReorderProxyGroupsToTopoOrder { |topoOrder|
		var keyItem;
		var ndItem;
		var proxyItem;
		var groupItem;

		keyItem = nil;
		ndItem = nil;
		proxyItem = nil;
		groupItem = nil;

		if(spaceGroup.isNil) {
			this.debugPost { "[DBG][GRAPH][REORDER] spaceGroup is nil" };
			^this;
		};

		if(topoOrder.isNil) {
			this.debugPost { "[DBG][GRAPH][REORDER] topoOrder is nil" };
			^this;
		};

		topoOrder.do { |kk|
			keyItem = kk;

			ndItem = envir.at(keyItem);
			proxyItem = nil;
			groupItem = nil;

			if(ndItem.isKindOf(NdM)) {
				try { proxyItem = ndItem.proxy; } { proxyItem = nil; };

				if(proxyItem.notNil) {
					try { groupItem = proxyItem.group; } { groupItem = nil; };

					if(groupItem.notNil) {
						try { groupItem.moveToTail(spaceGroup); } {
							this.debugPost { "[WARN][GRAPH][REORDER] moveToTail failed key=" ++ keyItem.asString };
						};
					};
				};
			};
		};

		^this;
	}

	/*** Direct call to runNow → Move to next tick sched ***/
	graphApplyRunTick {
		var res;
		var holdSec;
		var doRebuild;

		var traceId;
		var auxState;
		var auxValue;

		res = nil;

		// SPEC (Pending consumption point):
		// - graphApplyPending = false is the *only* pending consumption point.
		// - MUST NOT move this reset into requestGraphRebuild (or any other method).
		// - If pending is consumed on the request side, the pending hold duration becomes zero,
		//   breaking same-tick coalescing and causing T1 to FAIL.
		// Consume the scheduled flag; from here, pending is false (next call can schedule/run again).
		graphApplyPending = false;

		// Start running; rerun collects requests that arrive while running.
		graphApplyRunning = true;
		graphApplyRerun = false;

		auxState = ("pending:" ++ graphApplyPending.asString
			++ " running:" ++ graphApplyRunning.asString
			++ " rerun:" ++ graphApplyRerun.asString).asSymbol;
		auxValue = this.identityHash;

		traceId = this.traceBegin(\graphApplyRunTick, \none, -1, \none, -1, \none);
		this.tracePush('graph.run.beforeRebuild', \none, \graphApplyRunTick, -1, auxState, auxValue, 'reason:state', traceId);
		this.traceEnd(traceId, \graphApplyRunTick, \none, -1, \none, -1, \none);

		doRebuild = {
			res = this.rebuildGraphIfDirty;

			// If rebuildGraphIfDirty returned nil, no async tail is expected.
			// In that case, close running here.
			if(res.isNil) {
				graphApplyRunning = false;

				auxState = ("pending:" ++ graphApplyPending.asString
					++ " running:" ++ graphApplyRunning.asString
					++ " rerun:" ++ graphApplyRerun.asString).asSymbol;
				auxValue = this.identityHash;

				traceId = this.traceBegin(\graphApplyRunTick, \none, -1, \none, -1, \none);
				this.tracePush('graph.run.endImmediate', \none, \graphApplyRunTick, -1, auxState, auxValue, 'reason:endImmediate', traceId);

				if(graphApplyRerun) {
					this.tracePush('graph.req.rerun', \none, \graphApplyRunTick, -1, 'caller:graphApplyRunTick', auxValue, 'reason:rerun', traceId);

					graphApplyRerun = false;
					this.requestGraphRebuild;
				};

				this.traceEnd(traceId, \graphApplyRunTick, \none, -1, \none, -1, \none);
			};

			nil
		};

		// TEST HOOK (T2 only): extend running window WITH yield (no busy-wait).
		// - Keep graphApplyRunning=true during holdSec.
		// - Allow external requestGraphRebuild to enter with running=true.
		holdSec = graphApplyTestHoldSec;
		if(holdSec.notNil && (holdSec > 0.0)) {
			SystemClock.sched(holdSec, {
				doRebuild.()
			});
		} {
			doRebuild.()
		};

		^this
	}

	requestGraphRebuild {
		^this.requestGraphRebuildFrom(\none);
	}

	requestGraphRebuildFrom { |whereIn|
		^this.requestGraphRebuildWithWhere(whereIn);
	}

	requestGraphRebuildWithWhere { |whereIn|
		var doStart;
		var xresult;

		// [STEP2] migrate [TRACE][GRAPH][REQ] logs into [ROW] via tracePush
		var traceId;
		var pVal;
		var rVal;
		var rrVal;
		var hVal;
		var auxState;
		var auxReason;

		var whereLocal;

		// SPEC (Boundary contract):
		// - Responsibility is limited to:
		//   (1) state check  : graphApplyRunning / graphApplyPending
		//   (2) state update : graphApplyPending / graphApplyRerun
		//   (3) scheduling   : SystemClock.sched(...) to graphApplyRunTick
		// - This method MUST NOT execute rebuild:
		//   - MUST NOT call rebuildGraphIfDirty (directly or indirectly).
		// - graphApplyPending = false is consumed only at the beginning of graphApplyRunTick.
		//   - requestGraphRebuild MUST NOT reset graphApplyPending to false.
		// - graphApplyRunning is owned by graphApplyRunTick / async tail only.
		//   - requestGraphRebuild MUST NOT set graphApplyRunning true/false.

		whereLocal = whereIn ? \none;

		xresult = this;
		doStart = false;

		traceId = this.traceBegin(whereLocal, \none, -1, \none, -1, \none);

		// entrance snapshot -> [ROW]
		pVal = 0;
		rVal = 0;
		rrVal = 0;

		if(graphApplyPending) { pVal = 1; };
		if(graphApplyRunning) { rVal = 1; };
		if(graphApplyRerun) { rrVal = 1; };

		hVal = this.identityHash;

		this.tracePush('graph.req.received', \none, whereLocal, -1, \none, -1, \none, traceId);

		this.tracePush('graph.req.pending', \none, whereLocal, -1, \none, pVal, 'reason:state', traceId);
		this.tracePush('graph.req.running', \none, whereLocal, -1, \none, rVal, 'reason:state', traceId);
		this.tracePush('graph.req.rerun',   \none, whereLocal, -1, \none, rrVal, 'reason:state', traceId);

		this.tracePush('graph.req.thisHash', \none, whereLocal, -1, \none, hVal, 'reason:identity', traceId);

		// Branch A: running -> fold to rerun (no sched)
		if(graphApplyRunning) {
			graphApplyRerun = true;

			auxState = ("pending:" ++ graphApplyPending.asString
				++ " running:" ++ graphApplyRunning.asString
				++ " rerun:" ++ graphApplyRerun.asString).asSymbol;
			auxReason = 'reason:foldToRerun';

			this.tracePush('decision.foldToRerun', \none, whereLocal, -1, auxState, hVal, auxReason, traceId);

		} {
			// Branch B: pending -> keep pending (no sched)
			if(graphApplyPending) {

				this.tracePush('decision.skipAlreadyPending', \none, whereLocal, -1, \none, hVal, 'reason:alreadyPending', traceId);

			} {
				// Branch C: first -> set pending + schedule runTick
				doStart = true;

				auxState = ("doStart:" ++ doStart.asString).asSymbol;
				this.tracePush('graph.req.doStart', \none, whereLocal, -1, auxState, hVal, 'reason:first', traceId);

				graphApplyPending = true;

				SystemClock.sched(0.0, {
					this.graphApplyRunTick;
					nil
				});
			};
		};

		this.traceEnd(traceId, whereLocal, \none, -1, \none, -1, \none);

		^xresult
	}

	graphObserveSpaceGroupTree { |delaySecIn|
		var delaySec;
		var grp;
		var srv;

		delaySec = delaySecIn;
		if(delaySec.isNil) {
			delaySec = 0.2;
		};
		if(delaySec < 0.0) {
			delaySec = 0.0;
		};

		grp = spaceGroup;
		if(grp.isNil) {
			this.debugPost { "[DBG][GRAPH][OBS] spaceGroup is nil" };
			^this;
		};

		srv = server;
		if(srv.isNil) {
			srv = Server.default;
		};

		SystemClock.sched(delaySec, {
			this.debugPost {
				"[DBG][GRAPH][OBS] queryTree spaceGroup nodeID=" ++ grp.nodeID.asString
			};

			// Server.sync without action yields (illegal here).
			// Use action callback to avoid yield.
			if(srv.notNil) {
				try {
					srv.sync({
						grp.queryTree(true);
					});
				} {
					grp.queryTree(true);
				};
			} {
				grp.queryTree(true);
			};

			nil
		});

		^this
	}

	put { |key, obj|
		var specObj;
		var oldVal;
		var xr;

		oldVal = envir.at(key);

		if(obj.isKindOf(NdMSpaceSpec)) {
			specObj = obj;
			xr = this.putFromSpec(key, specObj, oldVal);
			^xr;
		};

		if(obj.isKindOf(Function)) {
			xr = this.putFunctionAsIs(key, obj, oldVal);
			^xr;
		};

		xr = this.putOtherAsIs(key, obj, oldVal);
		^xr;
	}

	putFromSpec { |key, specObj, oldVal|
		var ndmObj;
		var outBusResolved;
		var norm;
		var msgPutIn;
		var msgPutOut;
		var badOut;
		var dbgLocal;
		var monitor;
		var dirtyNow;
		var traceId;

		// --- argbus trace locals ---
		var auxState;
		var auxValue;

		traceId = this.traceBegin(\putFromSpec, key, -1, \none, -1, \none);
		this.tracePush(
			'putFromSpec.enter',
			key,
			\putFromSpec,
			-1,
			("oldValClass:" ++ oldVal.class.asString).asSymbol,
			-1,
			\none,
			traceId
		);

		this.putFromSpecInheritDbgToSpec(specObj, oldVal);

		dbgLocal = (spaceDbgValue ? false);

		msgPutIn = "[DBG][OUTBUS][PUT] key=" ++ key.asString
		++ " spec.outBus.class=" ++ specObj.outBus.class.asString
		++ " oldVal.class=" ++ oldVal.class.asString;
		if(dbgLocal) { msgPutIn.postln; };

		outBusResolved = this.putFromSpecResolveOutBus(specObj, oldVal);

		// Guard: resolve must not return NdMSpace (would poison NdM.outBus).
		badOut = false;
		if(outBusResolved.notNil) {
			if(outBusResolved.isKindOf(NdMSpace)) {
				badOut = true;
			};
		};
		if(badOut) {
			NdMError.reportOutBus(
				\outbusA,
				"NdMSpace.putFromSpec@A-guardType",
				key,
				\A,
				"putFromSpecResolveOutBus",
				outBusResolved,
				"fallback0"
			);

			outBusResolved = 0;
		};

		msgPutOut = "[DBG][OUTBUS][PUT] key=" ++ key.asString
		++ " outBusResolved.class=" ++ outBusResolved.class.asString
		++ " outBusResolved=" ++ outBusResolved.asString;
		if(dbgLocal) { msgPutOut.postln; };

		// argbus.use.write (writer): outBusResolved is finalized (Bus / Integer / Array)
		if(outBusResolved.isKindOf(Bus)) {
			auxState = 'outbus:Bus';
			auxValue = [outBusResolved.index];

			this.tracePush(
				'argbus.use.write',
				key,
				\putFromSpec,
				-1,
				auxState,
				auxValue,
				'reason:use',
				traceId
			);
		} {
			if(outBusResolved.isKindOf(Integer)) {
				auxState = 'outbus:Int';
				auxValue = [outBusResolved];

				this.tracePush(
					'argbus.use.write',
					key,
					\putFromSpec,
					-1,
					auxState,
					auxValue,
					'reason:use',
					traceId
				);
			} {
				if(outBusResolved.isKindOf(Array)) {
					auxState = 'outbus:Array';
					auxValue = outBusResolved.collect { |val|
						if(val.isKindOf(Bus)) {
							val.index
						} {
							val
						}
					};

					this.tracePush(
						'argbus.use.write',
						key,
						\putFromSpec,
						-1,
						auxState,
						auxValue,
						'reason:use',
						traceId
					);
				};
			};
		};

		ndmObj = NdM(key, specObj.func, outBusResolved, \putFromSpec);

		// Ensure graph bookkeeping (sink/edge) is restored even after NdMNameSpace.reset.
		ndmObj.ensureRegistered(outBusResolved);

		norm = this.putFromSpecApplyDbgAndUpdateLast(ndmObj, specObj, oldVal);

		if(specObj.fadeTime > 0) {
			ndmObj.fade = specObj.fadeTime;
		} {
			if(specObj.fadeTime == 0) {
				"[NdM] NOTE: fade(0) does not override existing fade time (use a small value like 0.001).".postln;
			};
		};

		specObj.applyTagsTo(ndmObj);

		envir.put(key, ndmObj);

		if(specObj.autoPlay) {
			this.tracePush(
				'autoPlay.consume',
				key,
				\putFromSpec_autoPlay,
				-1,
				\none,
				-1,
				\none,
				traceId
			);
			// == case A で f2 -> osc するには以下をコメントアウトしない ==
			// /* // Fix: capture dirtyNow as late as possible (right before autoPlay decision).
			monitor = NdMNameSpace.acquire;
			dirtyNow = false;
			if(monitor.notNil) {
				dirtyNow = monitor.graphDirtyValue;
			};

			if(dirtyNow) {
				this.requestGraphRebuildFrom(\putFromSpec_autoPlay);
			};
			// */
			this.debugPost { "[DBG][RESTORE][APPLY] where=putFromSpec_autoPlay key=" ++ key.asString
				++ " outBusResolved=" ++ outBusResolved.asString };
			ndmObj.play;
		};

		// (removed) graph rebuild trigger is unified to out()/out_()

		this.traceEnd(traceId, \putFromSpec, key, -1, \none, -1, \none);
		ndmObj
	}

	putFromSpecInheritDbgToSpec { |specObj, oldVal|
		var inheritDbg;

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

		nil
	}

	putFromSpecResolveOutBus { |specObj, oldVal|
		var outBusResolved;
		var badType;
		var src;
		var specCls;
		var oldCls;
		var oldOutVal;
		var oldOutCls;
		var msgResIn;
		var msgResPick;
		var msgResNorm;
		var dbgLocal;

		// SPEC:
		// - Resolve specObj.outBus to a *raw* out-bus target for storage into NdMSpace (A policy: raw-unify).
		// - Accept NdM as input and resolve here: NdM -> NdM.out.
		// - Return types are limited to: nil / Integer / Array(Integer) / Bus.
		// - Invalid types must be reported (NdMError(...).reportError) and must fall back to 0.

		// SPEC (Boundary contract):
		// - This method is the single, authoritative boundary for outBus / outTarget normalization.
		// - All downstream code must assume raw-only outputs from this method and must NOT
		//   perform any further normalization.

		// SPEC (境界契約):
		// - このメソッドは outBus / outTarget 正規化のための唯一かつ確定した境界である。
		// - このメソッドより下流のコードは raw-only（このメソッドの出力）を前提とし、
		//   追加の正規化処理を行ってはならない。

		outBusResolved = nil;
		badType = false;
		src = "nil";

		dbgLocal = (spaceDbgValue ? false);

		specCls = specObj.outBus.class.asString;
		oldCls = oldVal.class.asString;
		oldOutVal = nil;
		oldOutCls = "nil";
		if(oldVal.isKindOf(NdM)) {
			oldOutVal = oldVal.out;
			oldOutCls = oldOutVal.class.asString;
		};

		msgResIn = "[DBG][OUTBUS][RESOLVE] spec.outBus.class=" ++ specCls
		++ " oldVal.class=" ++ oldCls
		++ " oldVal.out.class=" ++ oldOutCls;
		if(dbgLocal) { msgResIn.postln; };

		// Prefer explicitly specified outBusResolved.
		if(specObj.outBus.notNil) {
			outBusResolved = specObj.outBus;
			src = "spec";
		} {
			// Otherwise inherit from existing NdM, or use defaultOut, or fall back to 0.
			if(oldVal.isKindOf(NdM)) {
				outBusResolved = oldOutVal;
				src = "inherit";
			} {
				if(defaultOutValue.notNil) {
					outBusResolved = defaultOutValue;
					src = "defaultOut";
				} {
					outBusResolved = 0;
					src = "default";
				};
			};
		};

		msgResPick = "[DBG][OUTBUS][RESOLVE] picked src=" ++ src
		++ " outBusResolved.class=" ++ outBusResolved.class.asString
		++ " outBusResolved=" ++ outBusResolved.asString;
		if(dbgLocal) { msgResPick.postln; };

		// Normalize: if outBusResolved is an NdM, use its out() value.
		if(outBusResolved.isKindOf(NdM)) {
			outBusResolved = outBusResolved.out;
			src = (src ++ ":ndm");
		};

		msgResNorm = "[DBG][OUTBUS][RESOLVE] after norm src=" ++ src
		++ " outBusResolved.class=" ++ outBusResolved.class.asString
		++ " outBusResolved=" ++ outBusResolved.asString;
		if(dbgLocal) { msgResNorm.postln; };

		// Guard: outBusResolved must be Integer / Array / Bus (NdMSpace etc are invalid).
		badType = true;
		if(outBusResolved.isKindOf(Integer)) { badType = false; };
		if(outBusResolved.isKindOf(Array)) { badType = false; };
		if(outBusResolved.isKindOf(Bus)) { badType = false; };

		if(badType) {
			NdMError.reportOutBus(
				\outbusB,
				"NdMSpace.putFromSpecResolveOutBus@B-guardType",
				nil,
				\B,
				"resolved",
				outBusResolved,
				"fallback0 src=" ++ src.asString
			);

			outBusResolved = 0;
		};

		^outBusResolved;
	}

	putFromSpecApplyDbgAndUpdateLast { |ndmObj, specObj, oldVal|
		var norm;

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
				norm = specObj.dbgValue;
			};
			lastDbgValue = norm;

		} {
			if(oldVal.isKindOf(NdM)) {
				if(oldVal.dbg) {
					ndmObj.dbg(1);
				};
			};
			norm = nil;
		};

		norm
	}

	putFunctionAsIs { |key, funcObj, oldVal|
		// If the previous value was an NdM, free it first (stop sound).
		if(oldVal.isKindOf(NdM)) {
			oldVal.free;
		};

		envir.put(key, funcObj);
		funcObj
	}

	putOtherAsIs { |key, obj, oldVal|
		// 3) Other objects (e.g. String) are stored as-is.
		//    If the previous value was an NdM, free it before overwriting.
		if(oldVal.isKindOf(NdM)) {
			oldVal.free;
		};

		envir.put(key, obj);
		obj
	}

	defaultOut {
		^defaultOutValue
	}

	defaultOut_ { |outBusIn|
		var outBusLocal;
		var badType;

		outBusLocal = outBusIn;
		badType = false;

		if(outBusLocal.notNil) {
			badType = true;
			if(outBusLocal.isKindOf(Integer)) { badType = false; };
			if(outBusLocal.isKindOf(Array)) { badType = false; };
			if(outBusLocal.isKindOf(Bus)) { badType = false; };
		};

		if(badType) {
			NdMError.reportOutBus(
				\outbusD,
				"NdMSpace.defaultOut_@D-guardType",
				\none,
				\D,
				"defaultOut",
				outBusLocal,
				"ignored"
			);
			^this;
		};

		defaultOutValue = outBusLocal;
		^this
	}

	at { |key|
		var obj;
		var ndmObj;

		obj = envir.at(key);

		if(obj.isNil) {
			this.tracePush('at.miss', key, \user, -1, \none, -1, \none, -1);
			this.debugPost { "[PATH][AT][MISS] key=" ++ key.asString };
			ndmObj = this.makeProxy(key, \user);
			this.debugPost {
				"[PATH][AT][PROXY] key=" ++ key.asString
				++ " created=true"
				++ " out.class=" ++ ndmObj.out.class.asString
			};
			^ndmObj;
		} {
			this.tracePush('at.hit', key, \user, -1, \none, -1, \none, -1);
			this.debugPost {
				"[PATH][AT][HIT] key=" ++ key.asString
				++ " class=" ++ obj.class.asString
			};
		};

		^obj;
	}

	makeProxy { |key, whereIn|
		var ndmObj;
		var whereLocal;
		var outBusLocal;

		whereLocal = whereIn;
		if(whereLocal.isNil) { whereLocal = \none; };

		ndmObj = envir.at(key);
		if(ndmObj.isKindOf(NdM)) {
			this.tracePush('proxy.exists', key, whereLocal, -1, \none, -1, \none, nil);
			this.debugPost {
				"[PATH][PROXY][EXISTS] key=" ++ key.asString
				++ " class=" ++ ndmObj.class.asString
			};
			^ndmObj;
		};

		this.tracePush('proxy.create', key, whereLocal, -1, \none, -1, \none, nil);
		this.debugPost { "[PATH][PROXY][CREATE] key=" ++ key.asString };

		outBusLocal = defaultOutValue;
		if(outBusLocal.isNil) {
			outBusLocal = 0;
		};

		ndmObj = NdM(key, { Silent.ar(1) }, outBusLocal, whereLocal);
		envir.put(key, ndmObj);

		this.tracePush(
			'proxy.created',
			key,
			whereLocal,
			-1,
			("outClass:" ++ ndmObj.out.class.asString).asSymbol,
			-1,
			("dbg:" ++ ndmObj.dbg.asString).asSymbol,
			nil
		);
		this.debugPost {
			"[PATH][PROXY][CREATED] key=" ++ key.asString
			++ " out=" ++ ndmObj.out.asString
			++ " dbg=" ++ ndmObj.dbg.asString
		};

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
		var msg;
		var badType;

		msg = nil;
		badType = false;

		if(outBusIn.notNil) {
			if(outBusIn.isKindOf(NdMSpace)) {
				badType = true;
			};
		};

		if(badType) {
			msg = "[NdMSpaceSpec.out] outBus must not be an NdMSpace instance.";
			Error(msg).throw;
		} {
			outBus = outBusIn;
		};

		^this;
	}

	fade { |fadeTimeIn|
		fadeTime = fadeTimeIn;
		^this;
	}

	play {
		var spaceLocal;
		var traceId;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\user, \none, -1, \none, -1, \none);
			spaceLocal.tracePush('spec.play.enter', \none, \user, -1, \none, -1, \none, traceId);
		};

		autoPlay = true;

		if(spaceLocal.notNil) {
			spaceLocal.tracePush('spec.play.mark', \none, \spec_play_mark, -1, \none, -1, \none, traceId);
			spaceLocal.traceEnd(traceId, \spec_play_mark, \none, -1, \none, -1, \none);
		};

		^this;
	}

	// aliases (for NdMSpaceSpec builder)
	o { |outTarget|
		^this.out(outTarget);
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
		var spaceLocal;
		var traceId;
		var auxState;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		auxState = ("tagBuffer.nil:" ++ tagBuffer.isNil.asString
			++ " ndm.nil:" ++ ndmInstance.isNil.asString).asSymbol;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\user, \none, -1, \none, -1, \none);
			spaceLocal.tracePush('spec.applyTagsTo.enter', \none, \spec_applyTagsTo, -1, auxState, -1, \none, traceId);
		};

		if(tagBuffer.notNil) {
			tagBuffer.do { |tagItem|
				ndmInstance.tag(tagItem);
			};
		};

		if(spaceLocal.notNil) {
			spaceLocal.traceEnd(traceId, \spec_applyTagsTo, \none, -1, \none, -1, \none);
		};
	}

	dbg { |value|
		var xr;
		var oldVal;
		var spaceLocal;
		var traceId;
		var auxState;

		oldVal = dbgValue;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		auxState = ("value:" ++ value.asString ++ " old:" ++ oldVal.asString).asSymbol;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\user, \none, -1, \none, -1, \none);
			spaceLocal.tracePush('spec.dbg.enter', \none, \spec_dbg, -1, auxState, -1, \none, traceId);
		};

		// getter
		if(value.isNil) {
			xr = dbgValue;
		} {
			dbgValue = value;
			xr = this;
		};

		if(spaceLocal.notNil) {
			spaceLocal.traceEnd(traceId, \spec_dbg, \none, -1, \none, -1, \none);
		};

		^xr;
	}

	dbg_ { |value|
		var oldVal;
		var spaceLocal;
		var traceId;
		var auxState;

		oldVal = dbgValue;

		spaceLocal = NdMSpace.current;
		traceId = nil;
		auxState = ("value:" ++ value.asString ++ " old:" ++ oldVal.asString).asSymbol;

		if(spaceLocal.notNil) {
			traceId = spaceLocal.traceBegin(\user, \none, -1, \none, -1, \none);
			spaceLocal.tracePush('spec.dbg_.enter', \none, \spec_dbg_, -1, auxState, -1, \none, traceId);
		};

		dbgValue = value;

		if(spaceLocal.notNil) {
			spaceLocal.traceEnd(traceId, \spec_dbg_, \none, -1, \none, -1, \none);
		};
	}

	debugPost { |msgFunc|
		var flag;
		var msg;

		// space dbg flag (Boolean)
		flag = this.dbg;

		if(flag == true) {
			// allow either String or Function for delayed construction
			if(msgFunc.isKindOf(Function)) {
				msg = msgFunc.value;
			} {
				msg = msgFunc;
			};

			if(msg.notNil) {
				msg.postln;
			};
		};

		^this;
	}
}


// Function → NdMSpaceSpec front-end

+ Function {
	nd {
		var xr;
		var space;
		var envOk;
		var msg;

		space = NdMSpace.current;
		envOk = currentEnvironment.isKindOf(NdMSpace);

		if(space.isNil || (envOk.not)) {
			msg = "[NdMSpace] nd{} is only available inside NdMSpace.enter/exit scope.";
			Error(msg).throw;
			xr = nil;
		} {
			xr = NdMSpaceSpec.fromFunction(this);
		};

		^xr;
	}
}
