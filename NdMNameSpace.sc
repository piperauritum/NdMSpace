/* ============================================================
NdMNameSpace — Persistent history monitor for NdM

PURPOSE:
- Keep a persistent history of
(key → argName → busIndex, argRate)
- Track *current* NdM instances (nodes)
- Keep tag information per key, and a reverse index tag → keys.
- Never delete arg history automatically
(arg removal happens only when NdM explicitly frees)
- Provide a single reset point: `.reset`
which wipes the live-node registry and graph cache,
but preserves arg history (key → argName → busIndex/rate).

DESIGN NOTES:
- `.reset` clears node/tag/graph bookkeeping, but keeps arg history for stable bus reuse.
- NdM.free / freeAll do not free Bus objects individually.
- History persists across NdM re-creations and across `.reset`.
- Nodes are added/removed explicitly by NdM.init / NdM.free.

DUMP OUTPUT:
dumpStatus prints:
-- Nodes --        : key / id / outbus / current arg list
-- argBusTable --  : recorded bus index and rate for each arg

OBSOLETE RELATIONS:
- NdMMon (old monitor) is deprecated.
NdMNameSpace fully replaces it as the authoritative bus-history store.
============================================================ */

NdMNameSpace : Object {

	classvar <monInst;
	classvar tagTable;

	var <stateByKey;
	var ownerByBusIndex;

	var <writersByReader;
	var <readersByWriter;
	var <sinkKeys;
	var <graphDirty;
	var <graphTouchedKeys;

	var <>feedbackWarnSig;

	// ------------------------------------------------
	// Singleton access
	// ------------------------------------------------

	// === Core class-side API ====================================================

	// acquire
	// ---------------------------------------------------------------------------
	// Core API: obtain the singleton NdMNameSpace instance.
	// - Creates and initializes the monitor on the first call.
	// - Returns the same instance on subsequent calls.
	// - Safe to call multiple times from NdM.

	*acquire {
		if(monInst.isNil) {
			monInst = super.new.init;
		};
		^monInst;
	}

	// instance
	// ---------------------------------------------------------------------------
	// Core API: return the current NdMNameSpace instance if it exists.
	// - Returns the existing singleton created by acquire.
	// - Returns nil if acquire has never been called.
	// - Used in places where the monitor is optional (e.g. free).

	*instance {
		^monInst;
	}

	*reset {
		// reset clears live nodes, tags, and graph cache.
		// arg history (design-layer) is preserved.

		var instLocal;
		var stateTable;
		var tagTable;

		"NdMNameSpace: reset".postln;

		// Policy:
		// - Preserve arg history (key -> argName -> busIndex/rate).
		// - Clear live-node registry, tags, and graph cache only.
		//
		// SPEC:
		// - Clears: live nodes registry, tags, graph cache (sinks/edges/touched/dirty).
		// - Preserves: arg history (key -> argName -> busIndex/rate).
		// - Does NOT reconstruct edges from existing NdM instances automatically.
		//   Graph bookkeeping is rebuilt only when nodes are (re)registered via out/out_ (put path).

		instLocal = monInst;
		if(instLocal.isNil) {
			monInst = super.new.init;
			^monInst;
		};

		stateTable = instLocal.stateByKey;

		// 1) Clear per-key node registry + tags, keep args as-is.
		this.resetClearLiveRegistryAndTags(instLocal, stateTable);

		// 2) Rebuild ownerByBusIndex from preserved arg history.
		this.resetRebuildOwnerByBusIndex(instLocal, stateTable);

		// 3) Clear tag table (reverse index) — tagTable is classvar in this file.
		tagTable = IdentityDictionary.new;

		// 4) Clear graph cache (instance vars; no setters)
		this.resetClearGraphCache(instLocal);

		^instLocal;
	}

	*resetClearGraphCache { |instLocal|
		var ivNames;
		var idxWritersByReader;
		var idxReadersByWriter;
		var idxSinkKeys;
		var idxGraphDirty;
		var idxGraphTouchedKeys;

		ivNames = instLocal.class.instVarNames;

		idxWritersByReader = ivNames.indexOf(\writersByReader);
		idxReadersByWriter = ivNames.indexOf(\readersByWriter);
		idxSinkKeys = ivNames.indexOf(\sinkKeys);
		idxGraphDirty = ivNames.indexOf(\graphDirty);
		idxGraphTouchedKeys = ivNames.indexOf(\graphTouchedKeys);

		if(idxWritersByReader.notNil) {
			instLocal.instVarPut(idxWritersByReader, IdentityDictionary.new);
		};
		if(idxReadersByWriter.notNil) {
			instLocal.instVarPut(idxReadersByWriter, IdentityDictionary.new);
		};
		if(idxSinkKeys.notNil) {
			instLocal.instVarPut(idxSinkKeys, IdentitySet.new);
		};
		if(idxGraphDirty.notNil) {
			instLocal.instVarPut(idxGraphDirty, false);
		};
		if(idxGraphTouchedKeys.notNil) {
			instLocal.instVarPut(idxGraphTouchedKeys, IdentitySet.new);
		};

		^instLocal;
	}

	*resetRebuildOwnerByBusIndex { |instLocal, stateTable|
		var ivNames;
		var idxOwnerByBusIndex;
		var ownerByBusIndexLocal;

		ivNames = instLocal.class.instVarNames;
		idxOwnerByBusIndex = ivNames.indexOf(\ownerByBusIndex);

		ownerByBusIndexLocal = IdentityDictionary.new;

		stateTable.keysValuesDo { |keySym, state|
			var argsDict;

			argsDict = state[\args];
			if(argsDict.notNil) {
				argsDict.keysValuesDo { |argName, argInfo|
					var busIndex;
					var rate;
					var ownerInfo;

					busIndex = argInfo[\busIndex];
					rate = argInfo[\rate];
					ownerInfo = nil;

					if(busIndex.notNil) {
						ownerInfo = IdentityDictionary.new;
						ownerInfo[\ownerKey] = keySym;
						ownerInfo[\argName] = argName;
						ownerInfo[\rate] = rate;

						ownerByBusIndexLocal[busIndex] = ownerInfo;
					};
				};
			};
		};

		if(idxOwnerByBusIndex.notNil) {
			instLocal.instVarPut(idxOwnerByBusIndex, ownerByBusIndexLocal);
		};

		^instLocal;
	}

	*resetClearLiveRegistryAndTags { |instLocal, stateTable|
		var keySym;
		var stateLocal;
		var nodeSet;
		var tagsSet;
		var argsTable;

		if(stateTable.notNil) {
			stateTable.keysValuesDo { |keyIn, stateIn|
				keySym = keyIn;
				stateLocal = stateIn;

				if(stateLocal.notNil) {
					nodeSet = IdentitySet.new;
					stateLocal[\node] = nodeSet;

					tagsSet = IdentitySet.new;
					stateLocal[\tags] = tagsSet;

					// keep args table (arg history)
					argsTable = stateLocal[\args];
					if(argsTable.isNil) {
						argsTable = IdentityDictionary.new;
						stateLocal[\args] = argsTable;
					};
				};
			};
		};

		^instLocal;
	}

	*release {
		// Reference counting and cleanup policy will be defined in the Phase 2 design.
		// This is a minimal skeleton; no action is taken here.
		^this;
	}

	*dumpStatus {
		var monitor;

		monitor = monInst;
		if(monitor.isNil) {
			"NdMNameSpace: no instance".postln;
		} {
			monitor.dumpStatus;
		};
		^this;
	}

	*dumpKey { |keySymbol|
		var monitor;

		monitor = monInst;
		if(monitor.isNil) {
			"NdMNameSpace: no instance".postln;
		} {
			monitor.dumpKey(keySymbol);
		};
		^this;
	}

	// ------------------------------------------------
	// init
	// ------------------------------------------------

	init {
		stateByKey = IdentityDictionary.new;
		tagTable = IdentityDictionary.new;
		ownerByBusIndex = IdentityDictionary.new;

		writersByReader = IdentityDictionary.new;
		readersByWriter = IdentityDictionary.new;
		sinkKeys = IdentitySet.new;
		graphDirty = false;
		graphTouchedKeys = IdentitySet.new;

		^this;
	}

	// ------------------------------------------------
	// per-key state helpers
	// state structure:
	//   \node -> IdentitySet[ NdM, ... ]
	//   \args -> IdentityDictionary[ argName -> { \busIndex, \rate } ]
	//   \tags -> IdentitySet[ Symbol, ... ]
	// ------------------------------------------------

	// === Internal utilities (not part of the public API) =======================

	// ensureKeyState
	// ---------------------------------------------------------------------------
	// Internal helper: make sure a state entry exists for the given key.
	// - Creates and returns a dictionary with:
	//   - \nodes -> IdentitySet[ NdM, ... ]
	//   - \args  -> IdentityDictionary[ argName -> { \busIndex, \rate } ]
	//   - \tags -> IdentitySet[ Symbol, ... ]
	// - Called only from core methods (register/rememberBus/rememberRate).
	// - Not intended to be used directly from outside NdMNameSpace.

	ensureKeyState { |keySymbol|
		var stateLocal;
		var nodeSet;
		var argsTable;
		var tagsSet;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			nodeSet = IdentitySet.new;
			argsTable = IdentityDictionary.new;
			tagsSet = IdentitySet.new;

			stateLocal = IdentityDictionary[
				\node -> nodeSet,
				\args -> argsTable,
				\tags -> tagsSet
			];

			stateByKey[keySymbol] = stateLocal;
		};
		^stateLocal;
	}

	// ------------------------------------------------
	// NdM registration (key → node set)
	// ------------------------------------------------

	// === Core instance-side API (used by NdM) ===================================

	// register
	// ---------------------------------------------------------------------------
	// Core API: register a live NdM instance under the given key.
	// - key: Symbol (NdM name).
	// - ndm: NdM instance.
	// - Ensures state for this key exists and adds ndm to the IdentitySet of nodes.
	// - Multiple registrations of the same ndm are ignored by the set.

	register { |keySymbol, ndmInstance|
		var stateLocal;
		var nodeSet;

		stateLocal = this.ensureKeyState(keySymbol);
		nodeSet = stateLocal[\node];

		if(nodeSet.notNil) {
			nodeSet.add(ndmInstance);
		};

		^this;
	}

	// unregister
	// ---------------------------------------------------------------------------
	// Core API: unregister a NdM instance for the given key.
	// - key: Symbol.
	// - ndm: NdM instance to remove.
	// - If the key is unknown, this is a no-op.
	// - Removes ndm from the nodes set, but keeps the args map for bus reuse.

	unregister { |keySymbol, ndmInstance|
		var stateLocal;
		var nodeSet;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.notNil) {
			nodeSet = stateLocal[\node];
			if(nodeSet.notNil) {
				nodeSet.remove(ndmInstance);
			};
		};

		^this;
	}

	// ------------------------------------------------
	// Arg-level bus / rate management
	// ------------------------------------------------

	// rememberBus
	// ---------------------------------------------------------------------------
	// Core API: store the bus index used by a given argument of a key.
	// - key: Symbol (NdM name).
	// - argName: Symbol (argument name, e.g. \frq, \pan).
	// - busIndex: Integer (server bus index).
	// - Initializes the per-key/per-arg state if needed.
	// - Does not touch the actual Bus object, only records the index.

	rememberBus { |keySymbol, argName, busIndex|
		var stateLocal;
		var argsTable;
		var argInfo;
		var ownerInfo;

		stateLocal = this.ensureKeyState(keySymbol);
		argsTable = stateLocal[\args];

		if(argsTable.isNil) {
			argsTable = IdentityDictionary.new;
			stateLocal[\args] = argsTable;
		};

		argInfo = argsTable[argName];
		if(argInfo.isNil) {
			argInfo = IdentityDictionary.new;
			argsTable[argName] = argInfo;
		};

		argInfo[\busIndex] = busIndex;

		ownerInfo = ownerByBusIndex[busIndex];
		if(ownerInfo.isNil) {
			ownerInfo = IdentityDictionary.new;
			ownerByBusIndex[busIndex] = ownerInfo;
		};

		ownerInfo[\ownerKey] = keySymbol;
		ownerInfo[\argName] = argName;

		^this;
	}

	// recallBus
	// ---------------------------------------------------------------------------
	// Core API: retrieve the last stored bus index for a given key/arg.
	// - key: Symbol.
	// - argName: Symbol.
	// - Returns Integer bus index if recorded, or nil if no entry exists.
	// - Used by NdM to reuse the same bus index across re-creations.

	recallBus { |keySymbol, argName|
		var stateLocal;
		var argsTable;
		var argInfo;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			^nil;
		};

		argsTable = stateLocal[\args];
		if(argsTable.isNil) {
			^nil;
		};

		argInfo = argsTable[argName];
		if(argInfo.isNil) {
			^nil;
		};

		^argInfo[\busIndex];
	}

	removeArg { |keySymbol, argName|
		var stateLocal;
		var argsTable;
		var argInfo;
		var busIndexLocal;
		var ownerInfo;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			^this;
		};

		argsTable = stateLocal[\args];
		if(argsTable.isNil) {
			^this;
		};

		argInfo = argsTable[argName];
		if(argInfo.isNil) {
			^this;
		};

		busIndexLocal = argInfo[\busIndex];

		argsTable.removeAt(argName);

		if(busIndexLocal.notNil) {
			ownerInfo = ownerByBusIndex[busIndexLocal];
			if(ownerInfo.notNil) {
				if((ownerInfo[\ownerKey] == keySymbol) && (ownerInfo[\argName] == argName)) {
					ownerByBusIndex.removeAt(busIndexLocal);
				};
			};
		};

		^this;
	}

	// rememberRate
	// ---------------------------------------------------------------------------
	// Core API: store the rate used by a given argument of a key.
	// - key: Symbol.
	// - argName: Symbol.
	// - rate: Symbol (usually \audio or \control).
	// - Initializes the per-key/per-arg state if needed.
	// - This is metadata only; it does not allocate or free any Bus.

	rememberRate { |keySymbol, argName, rateSymbol|
		var stateLocal;
		var argsTable;
		var argInfo;
		var busIndexLocal;
		var ownerInfo;

		stateLocal = this.ensureKeyState(keySymbol);
		argsTable = stateLocal[\args];

		if(argsTable.isNil) {
			argsTable = IdentityDictionary.new;
			stateLocal[\args] = argsTable;
		};

		argInfo = argsTable[argName];
		if(argInfo.isNil) {
			argInfo = IdentityDictionary.new;
			argsTable[argName] = argInfo;
		};

		argInfo[\rate] = rateSymbol;

		busIndexLocal = argInfo[\busIndex];
		if(busIndexLocal.notNil) {
			ownerInfo = ownerByBusIndex[busIndexLocal];
			if(ownerInfo.isNil) {
				ownerInfo = IdentityDictionary.new;
				ownerByBusIndex[busIndexLocal] = ownerInfo;
				ownerInfo[\ownerKey] = keySymbol;
				ownerInfo[\argName] = argName;
			};
			ownerInfo[\rate] = rateSymbol;
		};

		^this;
	}

	ownerForBusIndex { |busIndex|
		^ownerByBusIndex[busIndex];
	}

	// ensureOwnerForBusIndex
	// ---------------------------------------------------------------------------
	// Fallback: if ownerByBusIndex lacks an entry (should be rare),
	// rebuild it by scanning preserved arg history (stateByKey[\args]).
	// Returns ownerInfo or nil.
	ensureOwnerForBusIndex { |busIndex|
		var ownerInfo;
		var stateTable;
		var found;
		var foundKey;
		var foundArg;
		var foundRate;
		var stateLocal;
		var argsTable;
		var argInfo;
		var busIndexLocal;
		var rateLocal;

		ownerInfo = ownerByBusIndex[busIndex];
		if(ownerInfo.notNil) {
			^ownerInfo;
		};

		stateTable = stateByKey;
		if(stateTable.isNil) {
			^nil;
		};

		found = false;
		foundKey = nil;
		foundArg = nil;
		foundRate = nil;

		stateTable.keysValuesDo { |k, st|
			stateLocal = st;
			if(found.not && stateLocal.notNil) {
				argsTable = stateLocal[\args];
				if(argsTable.notNil) {
					argsTable.keysValuesDo { |argName, info|
						argInfo = info;
						if(found.not && argInfo.notNil) {
							busIndexLocal = argInfo[\busIndex];
							if(busIndexLocal.notNil && (busIndexLocal == busIndex)) {
								rateLocal = argInfo[\rate];

								found = true;
								foundKey = k;
								foundArg = argName;
								foundRate = rateLocal;
							};
						};
					};
				};
			};
		};

		if(found) {
			ownerInfo = IdentityDictionary.new;
			ownerInfo[\ownerKey] = foundKey;
			ownerInfo[\argName] = foundArg;
			ownerInfo[\rate] = foundRate;
			ownerByBusIndex[busIndex] = ownerInfo;
		};

		^ownerInfo;
	}

	registerEdge { |writerKey, busIndex|
		var ownerInfo;
		var readerKey;
		var wset;
		var rset;
		var changed;

		changed = false;
		ownerInfo = ownerByBusIndex[busIndex];
		if(ownerInfo.isNil) {
			^false; // external bus: no edge
		};

		readerKey = ownerInfo[\ownerKey];

		wset = writersByReader[readerKey];
		if(wset.isNil) {
			wset = IdentitySet.new;
			writersByReader[readerKey] = wset;
		};

		if(wset.includes(writerKey).not) {
			wset.add(writerKey);
			changed = true;
		};

		rset = readersByWriter[writerKey];
		if(rset.isNil) {
			rset = IdentitySet.new;
			readersByWriter[writerKey] = rset;
		};

		if(rset.includes(readerKey).not) {
			rset.add(readerKey);
			changed = true;
		};

		if(changed) {
			graphDirty = true;
			graphTouchedKeys.add(writerKey);
			graphTouchedKeys.add(readerKey);
		};

		^changed;
	}

	unregisterEdge { |writerKey, busIndex|
		var ownerInfo;
		var readerKey;
		var wset;
		var rset;
		var changed;

		changed = false;

		ownerInfo = ownerByBusIndex[busIndex];
		if(ownerInfo.isNil) {
			^false; // external bus: no edge
		};

		readerKey = ownerInfo[\ownerKey];

		wset = writersByReader[readerKey];
		if(wset.notNil) {
			if(wset.includes(writerKey)) {
				wset.remove(writerKey);
				changed = true;

				if(wset.isEmpty) {
					writersByReader.removeAt(readerKey);
				};
			};
		};

		rset = readersByWriter[writerKey];
		if(rset.notNil) {
			if(rset.includes(readerKey)) {
				rset.remove(readerKey);
				changed = true;

				if(rset.isEmpty) {
					readersByWriter.removeAt(writerKey);
				};
			};
		};

		if(changed) {
			graphDirty = true;
			graphTouchedKeys.add(writerKey);
			graphTouchedKeys.add(readerKey);
		};

		^changed;
	}

	// === Graph restore API (for reset + reuse) ===
	//
	// Rebuild:
	//   - ownership metadata (busIndex -> ownerKey/argName) from node.argBuses/argRates
	//   - sink/edge bookkeeping from outbusIn
	//
	// This allows edge restoration after NdMNameSpace.reset when NdM instances are reused.
	restoreFromNode { |keySymbol, node, outTarget|
		var busesLocal;
		var ratesLocal;
		var outTargetLocal;
		var busLocal;
		var busIndexLocal;
		var rateLocal;
		var hasEdge;

		// Always restore the live node registry first.
		this.register(keySymbol, node);

		// (1) Restore ownership map from the node's cached arg buses/rates.
		busesLocal = node.argBuses;
		ratesLocal = node.argRates;

		if(busesLocal.notNil) {
			busesLocal.keysValuesDo { |argName, busObj|
				busLocal = busObj;
				if(busLocal.notNil) {
					busIndexLocal = busLocal.index;

					rateLocal = \audio;
					if(ratesLocal.notNil) {
						rateLocal = ratesLocal[argName] ? \audio;
					};

					this.rememberBus(keySymbol, argName, busIndexLocal);
					this.rememberRate(keySymbol, argName, rateLocal);
				};
			};
		};

		// (2) Restore sink/edge from outTarget.
		outTargetLocal = outTarget;
		hasEdge = false;

		if(outTargetLocal.isNil) {
			this.unmarkSink(keySymbol);
		} {
			if(outTargetLocal.isKindOf(Bus)) {
				busIndexLocal = outTargetLocal.index;
				hasEdge = this.registerEdge(keySymbol, busIndexLocal);

				if(hasEdge) {
					this.unmarkSink(keySymbol);
				} {
					// external bus => sink
					this.markSink(keySymbol);
				};
			} {
				if(outTargetLocal.isKindOf(Integer) || outTargetLocal.isKindOf(Array)) {
					this.markSink(keySymbol);
				} {
					this.unmarkSink(keySymbol);
				};
			};
		};

		^this;
	}

	markSink { |key|
		var wasIn;
		var changed;

		wasIn = sinkKeys.includes(key);
		sinkKeys.add(key);
		changed = wasIn.not;

		if(changed) {
			graphDirty = true;
			graphTouchedKeys.add(key);
		};

		^changed;
	}

	unmarkSink { |key|
		var wasIn;
		var changed;

		wasIn = sinkKeys.includes(key);
		sinkKeys.remove(key);
		changed = wasIn;

		if(changed) {
			graphDirty = true;
			graphTouchedKeys.add(key);
		};

		^changed;
	}

	// removeGraphKey
	// ---------------------------------------------------------------------------
	// Graph API: remove all sink/edge relations for the given key.
	// - Removes:
	//   - key as writer: readersByWriter[key], and corresponding writersByReader[reader]
	//   - key as reader: writersByReader[key], and corresponding readersByWriter[writer]
	//   - key as sink: sinkKeys
	// - Marks graphDirty and graphTouchedKeys for all affected keys when changed.

	removeGraphKey { |keySymbol|
		var changed;
		var readersSet;
		var writersSet;
		var readerKey;
		var writerKey;
		var wset;
		var rset;
		var touchedSet;
		var touchedKey;

		changed = false;
		touchedSet = IdentitySet.new;

		// 1) key as writer: remove edges (key -> readers)
		readersSet = readersByWriter[keySymbol];
		if(readersSet.notNil) {
			readersSet.asArray.do { |rk|
				readerKey = rk;
				wset = writersByReader[readerKey];
				if(wset.notNil) {
					if(wset.includes(keySymbol)) {
						wset.remove(keySymbol);
						changed = true;

						touchedSet.add(keySymbol);
						touchedSet.add(readerKey);

						if(wset.isEmpty) {
							writersByReader.removeAt(readerKey);
						};
					};
				};
			};

			readersByWriter.removeAt(keySymbol);
			changed = true;
			touchedSet.add(keySymbol);
		};

		// 2) key as reader: remove edges (writers -> key)
		writersSet = writersByReader[keySymbol];
		if(writersSet.notNil) {
			writersSet.asArray.do { |wk|
				writerKey = wk;
				rset = readersByWriter[writerKey];
				if(rset.notNil) {
					if(rset.includes(keySymbol)) {
						rset.remove(keySymbol);
						changed = true;

						touchedSet.add(keySymbol);
						touchedSet.add(writerKey);

						if(rset.isEmpty) {
							readersByWriter.removeAt(writerKey);
						};
					};
				};
			};

			writersByReader.removeAt(keySymbol);
			changed = true;
			touchedSet.add(keySymbol);
		};

		// 3) key as sink
		if(sinkKeys.includes(keySymbol)) {
			sinkKeys.remove(keySymbol);
			changed = true;
			touchedSet.add(keySymbol);
		};

		if(changed) {
			graphDirty = true;

			touchedSet.asArray.do { |k|
				touchedKey = k;
				graphTouchedKeys.add(touchedKey);
			};
		};

		^changed;
	}

	sinkKeysCopy {
		^sinkKeys.copy;
	}

	graphTouchedKeysCopy {
		^graphTouchedKeys.copy;
	}

	graphDirtyValue {
		^graphDirty;
	}

	clearGraphDirty {
		graphDirty = false;
		graphTouchedKeys.clear;
		^this;
	}

	graphOrder {
		// Returns an Array of keys in topological order (writers before readers).
		// If no sinks are registered, returns an empty Array.
		var sinksArr;
		var order;

		sinksArr = this.graphOrderSinksArray;
		order = Array.new;

		if(sinksArr.isEmpty.not) {
			order = this.graphOrderTopoFromSinks(sinksArr);
		};

		^order;
	}

	graphOrderSinksArray {
		var sinksArr;

		sinksArr = Array.new;

		if(sinkKeys.notNil) {
			sinksArr = sinkKeys.asArray;
		};

		^sinksArr;
	}

	graphOrderTopoFromSinks { |sinksArr|
		// Internal: topo order restricted to the subgraph reachable from sinks.
		var order;
		var reachable;
		var queue;
		var indeg;
		var nodeSet;
		var keyItem;
		var readers;
		var writersSet;
		var degVal;

		order = Array.new;
		reachable = IdentitySet.new;
		queue = Array.new;
		indeg = IdentityDictionary.new;
		nodeSet = IdentitySet.new;

		// 1) Collect reachable nodes by walking upstream from sinks.
		queue = sinksArr.copy;
		queue.do { |sinkKey|
			reachable.add(sinkKey);
		};

		while { queue.notEmpty } {
			keyItem = queue.removeAt(0);
			writersSet = writersByReader[keyItem];

			if(writersSet.notNil) {
				writersSet.asArray.do { |writerKey|
					if(reachable.includes(writerKey).not) {
						reachable.add(writerKey);
						queue = queue.add(writerKey);
					};
				};
			};
		};

		// 2) Build nodeSet and indegree within the reachable subgraph.
		reachable.asArray.do { |nodeKey|
			nodeSet.add(nodeKey);
			indeg[nodeKey] = 0;
		};

		reachable.asArray.do { |writerKey|
			readers = readersByWriter[writerKey];

			if(readers.notNil) {
				readers.asArray.do { |readerKey|
					if(nodeSet.includes(readerKey)) {
						degVal = indeg[readerKey];
						if(degVal.isNil) { degVal = 0; };
						indeg[readerKey] = (degVal + 1);
					};
				};
			};
		};

		// 3) Kahn: start with indegree 0 nodes.
		queue = Array.new;
		nodeSet.asArray.do { |nodeKey|
			degVal = indeg[nodeKey];
			if(degVal.isNil) { degVal = 0; };

			if(degVal == 0) {
				queue = queue.add(nodeKey);
			};
		};
		queue = queue.sort { |aa, bb| (aa.asString < bb.asString) };

		while { queue.notEmpty } {
			keyItem = queue.removeAt(0);
			order = order.add(keyItem);

			readers = readersByWriter[keyItem];
			if(readers.notNil) {
				readers.asArray.do { |readerKey|
					if(nodeSet.includes(readerKey)) {
						degVal = indeg[readerKey];
						if(degVal.isNil) { degVal = 0; };

						degVal = degVal - 1;
						indeg[readerKey] = degVal;

						if(degVal == 0) {
							if(queue.includes(readerKey).not) {
								queue = queue.add(readerKey);
								queue = queue.sort { |aa, bb| (aa.asString < bb.asString) };
							};
						};
					};
				};
			};
		};

		^order;
	}

	computeRebuildOrder { |activeKeys|
		// Returns a topological order limited to activeKeys.
		//
		// Policy:
		// - B (default): start from sinks = (sinkKeys ∩ activeKeys), then walk upstream to collect reachable.
		// - A (fallback): if sinks is empty, treat all activeKeys as sinks (so reachable becomes all active).
		//
		// Note:
		// - This method only computes the order. NdMSpace applies rebuild only to touched keys.
		// - Feedback loop is NOT allowed for NdM bus assignment. This method reports detection info.
		var order;
		var activeSet;
		var sinksArr;
		var reachable;
		var indeg;
		var nodeSet;
		var missing;
		var hasFeedbackLoop;
		var res;

		order = Array.new;
		activeSet = this.computeRebuildOrderActiveSet(activeKeys);

		res = IdentityDictionary.new;

		if(activeSet.isEmpty) {
			res[\order] = order;
			res[\hasFeedbackLoop] = false;
			res[\feedbackMissing] = Array.new;
			^res;
		};

		sinksArr = this.computeRebuildOrderSinks(activeSet);
		reachable = this.computeRebuildOrderReachable(activeSet, sinksArr);

		# nodeSet, indeg = this.computeRebuildOrderIndeg(reachable);

		order = this.computeRebuildOrderKahn(nodeSet, indeg);

		# hasFeedbackLoop, missing, order = this.computeRebuildOrderFinalize(nodeSet, order);

		res[\order] = order;
		res[\hasFeedbackLoop] = hasFeedbackLoop;
		res[\feedbackMissing] = missing;

		^res;
	}

	computeRebuildOrderActiveSet { |activeKeys|
		var activeSet;

		activeSet = IdentitySet.new;

		if(activeKeys.notNil) {
			activeKeys.asArray.do { |ak|
				activeSet.add(ak);
			};
		};

		^activeSet;
	}

	computeRebuildOrderSinks { |activeSet|
		var sinksArr;

		sinksArr = Array.new;

		if(sinkKeys.notNil) {
			sinkKeys.asArray.do { |sk|
				if(activeSet.includes(sk)) {
					sinksArr = sinksArr.add(sk);
				};
			};
		};

		if(sinksArr.isEmpty) {
			sinksArr = activeSet.asArray;
		};

		^sinksArr;
	}

	computeRebuildOrderReachable { |activeSet, sinksArr|
		var reachable;
		var queue;
		var k;
		var wset;

		reachable = IdentitySet.new;
		queue = sinksArr.copy;

		queue.do { |sinkKey|
			reachable.add(sinkKey);
		};

		while { queue.notEmpty } {
			k = queue.removeAt(0);
			wset = writersByReader[k];

			if(wset.notNil) {
				wset.asArray.do { |wk|
					if(activeSet.includes(wk) && (reachable.includes(wk).not)) {
						reachable.add(wk);
						queue = queue.add(wk);
					};
				};
			};
		};

		^reachable;
	}

	computeRebuildOrderIndeg { |reachable|
		var indeg;
		var nodeSet;
		var readers;
		var kk;

		indeg = IdentityDictionary.new;
		nodeSet = IdentitySet.new;

		reachable.asArray.do { |nk|
			nodeSet.add(nk);
			indeg[nk] = 0;
		};

		reachable.asArray.do { |writerKey|
			readers = readersByWriter[writerKey];
			if(readers.notNil) {
				readers.asArray.do { |readerKey|
					if(nodeSet.includes(readerKey)) {
						kk = indeg[readerKey];
						if(kk.isNil) { kk = 0; };
						indeg[readerKey] = (kk + 1);
					};
				};
			};
		};

		^ [ nodeSet, indeg ];
	}

	computeRebuildOrderKahn { |nodeSet, indeg|
		var order;
		var queue;
		var k;
		var readers;
		var kk;

		order = Array.new;
		queue = Array.new;

		nodeSet.asArray.do { |nk|
			kk = indeg[nk];
			if(kk.isNil) { kk = 0; };

			if(kk == 0) {
				queue = queue.add(nk);
			};
		};

		queue = queue.sort { |a, b| (a.asString < b.asString) };

		while { queue.notEmpty } {
			k = queue.removeAt(0);
			order = order.add(k);

			readers = readersByWriter[k];
			if(readers.notNil) {
				readers.asArray.do { |rk|
					if(nodeSet.includes(rk)) {
						kk = indeg[rk];
						if(kk.isNil) { kk = 0; };
						kk = kk - 1;
						indeg[rk] = kk;

						if(kk == 0) {
							if(queue.includes(rk).not) {
								queue = queue.add(rk);
								queue = queue.sort { |a, b| (a.asString < b.asString) };
							};
						};
					};
				};
			};
		};

		^order;
	}

	computeRebuildOrderFinalize { |nodeSet, orderIn|
		var remain;
		var hasFeedbackLoop;
		var missing;
		var missKey;
		var order;

		order = orderIn;
		remain = (nodeSet.size - order.size);
		hasFeedbackLoop = (remain > 0);

		missing = Array.new;
		missKey = nil;

		if(hasFeedbackLoop) {
			nodeSet.asArray.do { |nk|
				missKey = nk;
				if(order.includes(missKey).not) {
					missing = missing.add(missKey);
				};
			};

			missing = missing.sort { |a, b| (a.asString < b.asString) };

			// Policy: feedback loop => no valid topological order.
			order = nil;
		};

		^ [ hasFeedbackLoop, missing, order ];
	}

	// recallRate
	// ---------------------------------------------------------------------------
	// Core API: retrieve the last stored rate for a given key/arg.
	// - key: Symbol.
	// - argName: Symbol.
	// - Returns a Symbol (typically \audio or \control) or nil if unknown.
	// - Optional helper; NdM may fall back to its own default if nil is returned.

	recallRate { |keySymbol, argName|
		var stateLocal;
		var argsTable;
		var argInfo;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			^nil;
		};

		argsTable = stateLocal[\args];
		if(argsTable.isNil) {
			^nil;
		};

		argInfo = argsTable[argName];
		if(argInfo.isNil) {
			^nil;
		};

		^argInfo[\rate];
	}

	// ------------------------------------------------
	// Tag management (low-level API)
	// ------------------------------------------------

	addTag { |keySymbol, tagSymbol|
		var stateLocal;
		var tagSet;
		var nameSet;
		var message;
		var hasError;

		hasError = false;

		if(tagSymbol.isKindOf(Symbol).not) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] addTag: expected Symbol for tag, got "
			++ tagSymbol.asString;
			message.postln;
			hasError = true;
		};

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] addTag: name '"
			++ keySymbol.asString
			++ "' is not registered";
			message.postln;
			hasError = true;
		};

		if(hasError.not) {
			tagSet = stateLocal[\tags];
			if(tagSet.isNil) {
				tagSet = IdentitySet.new;
				stateLocal[\tags] = tagSet;
			};
			tagSet.add(tagSymbol);

			if(tagTable.isNil) {
				tagTable = IdentityDictionary.new;
			};

			nameSet = tagTable[tagSymbol];
			if(nameSet.isNil) {
				nameSet = IdentitySet.new;
				tagTable[tagSymbol] = nameSet;
			};
			nameSet.add(keySymbol);
		};

		^this;
	}

	removeTag { |keySymbol, tagSymbol|
		var stateLocal;
		var tagSet;
		var nameSet;
		var message;
		var hasError;

		hasError = false;

		if(tagSymbol.isKindOf(Symbol).not) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] removeTag: expected Symbol for tag, got "
			++ tagSymbol.asString;
			message.postln;
			hasError = true;
		};

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] removeTag: name '"
			++ keySymbol.asString
			++ "' is not registered";
			message.postln;
			hasError = true;
		};

		if(hasError.not) {
			tagSet = stateLocal[\tags];
			if(tagSet.notNil) {
				tagSet.remove(tagSymbol);
			};

			if(tagTable.notNil) {
				nameSet = tagTable[tagSymbol];
				if(nameSet.notNil) {
					nameSet.remove(keySymbol);
					if(nameSet.isEmpty) {
						tagTable.removeAt(tagSymbol);
					};
				};
			};
		};

		^this;
	}

	tagsFor { |keySymbol|
		var stateLocal;
		var tagSet;
		var result;
		var message;

		stateLocal = stateByKey[keySymbol];
		if(stateLocal.isNil) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] tagsFor: name '"
			++ keySymbol.asString
			++ "' is not registered";
			message.postln;
			result = Array.new;
		} {
			tagSet = stateLocal[\tags];
			if(tagSet.isNil) {
				result = Array.new;
			} {
				result = tagSet.asArray;
			};
		};

		^result;
	}

	namesForTag { |tagSymbol|
		var namesSet;
		var result;
		var message;

		result = Array.new;

		if(tagSymbol.isKindOf(Symbol).not) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] namesForTag: expected Symbol for tag, got "
			++ tagSymbol.asString;
			message.postln;
		} {
			if(tagTable.notNil) {
				namesSet = tagTable[tagSymbol];
			} {
				namesSet = nil;
			};

			if(namesSet.isNil) {
				message = String.new;
				message = message
				++ "[NdMNameSpace] namesForTag: tag '"
				++ tagSymbol.asString
				++ "' is not registered";
				message.postln;
			} {
				result = namesSet.asArray;
			};
		};

		^result;
	}

	nodesForTag { |tagSymbol|
		var names;
		var nodes;
		var ndmInstance;
		var message;

		nodes = Array.new;

		if(tagSymbol.isKindOf(Symbol).not) {
			message = String.new;
			message = message
			++ "[NdMNameSpace] nodesForTag: expected Symbol for tag, got "
			++ tagSymbol.asString;
			message.postln;
		} {
			names = this.namesForTag(tagSymbol);
			names.do { |keySymbol|
				ndmInstance = NdM.find(keySymbol);
				if(ndmInstance.notNil) {
					nodes = nodes.add(ndmInstance);
				};
			};
		};

		^nodes;
	}

	allTags {
		var result;

		if(tagTable.isNil) {
			result = Array.new;
		} {
			result = tagTable.keys.asArray;
		};
		^result;
	}

	// ------------------------------------------------
	// Debug / observation API
	// ------------------------------------------------

	// === Debug / observation API ================================================

	// dumpStatus
	// ---------------------------------------------------------------------------
	// Debug API: print the current monitor state to the post window.
	// - Shows all keys sorted by name.
	// - For each key, prints:
	//   - nodes: IdentitySet of live NdM instances.
	//   - args : IdentityDictionary mapping argName -> { busIndex, rate }.
	// - Intended for human inspection and regression tests.
	// - Does not modify any internal state.

	dumpKeyBody { |keySymbol, stateLocal, addBlank|
		var nodeSet;
		var argsTable;
		var tagsSet;
		var nodeArray;
		var nodeLine;
		var argsKeys;
		var argName;
		var argInfo;
		var busIndex;
		var rate;
		var argsHasAny;
		var tagsArray;
		var tagsLine;

		nodeSet   = stateLocal[\node];
		argsTable = stateLocal[\args];
		tagsSet   = stateLocal[\tags];

		// --- nodes line ---
		nodeArray = if(nodeSet.notNil) { nodeSet.asArray } { Array.new };
		nodeLine = "  nodes: [ ";
		nodeArray.do { |ndm, idx|
			nodeLine = nodeLine ++ ndm.asString;
			if(idx < (nodeArray.size - 1)) {
				nodeLine = nodeLine ++ ", ";
			};
		};
		nodeLine = nodeLine ++ " ]";

		("key: " ++ keySymbol).postln;
		nodeLine.postln;

		// --- args lines ---
		if(argsTable.isNil or: { argsTable.isEmpty }) {
			"  args : [ ]".postln;
		} {
			"  args : [".postln;

			argsHasAny = false;
			argsKeys = argsTable.keys;

			argsKeys.do { |argKey|
				argName = argKey;
				argInfo = argsTable[argName];

				if(argInfo.notNil) {
					busIndex = argInfo[\busIndex];
					rate = argInfo[\rate];

					(
						"    (" ++ argName
						++ " -> [ (busIndex -> " ++ busIndex ++ "), (rate -> " ++ rate ++ ") ])"
					).postln;

					argsHasAny = true;
				};
			};

			if(argsHasAny.not) {
				"    (none)".postln;
			};

			"  ]".postln;
		};

		// --- tags line ---
		if(tagsSet.isNil or: { tagsSet.isEmpty }) {
			"  tags : [ ]".postln;
		} {
			tagsArray = tagsSet.asArray;
			tagsLine = "  tags : [ ";
			tagsArray.do { |tagItem, tagIndex|
				tagsLine = tagsLine ++ tagItem.asString;
				if(tagIndex < (tagsArray.size - 1)) {
					tagsLine = tagsLine ++ ", ";
				};
			};
			tagsLine = tagsLine ++ " ]";
			tagsLine.postln;
		};

		if(addBlank) {
			"".postln;
		};
	}

	dumpStatus {
		var keyList;
		var keySymbol;
		var stateLocal;
		var sinkLine;
		var touchedLine;
		var wLine;
		var rLine;

		"===== NdMNameSpace status =====".postln;

		if(stateByKey.isNil) {
			"stateByKey is nil".postln;
			"==========================".postln;
			^this;
		};

		keyList = stateByKey.keys;

		keyList.do { |keyItem|
			keySymbol = keyItem;
			stateLocal = stateByKey[keySymbol];
			if(stateLocal.notNil) {
				this.dumpKeyBody(keySymbol, stateLocal, true);
			};
		};

		"----- graph -----".postln;

		// Summary line (compact)
		sinkLine = if(sinkKeys.isNil || sinkKeys.isEmpty) {
			"sinks=[]";
		} {
			"sinks=[" ++ sinkKeys.asArray.sort.collect { |k| k.asString }.join(", ") ++ "]";
		};

		touchedLine = if(graphTouchedKeys.isNil || graphTouchedKeys.isEmpty) {
			"touched=[]";
		} {
			"touched=[" ++ graphTouchedKeys.asArray.sort.collect { |k| k.asString }.join(", ") ++ "]";
		};

		("dirty=" ++ graphDirty.asString ++ " " ++ sinkLine ++ " " ++ touchedLine).postln;

		// Edges (reader <- writers)
		"edges (reader <- writers):".postln;
		if(writersByReader.isNil || writersByReader.isEmpty) {
			"  (none)".postln;
		} {
			writersByReader.keys.asArray.sort.do { |readerKey|
				wLine = "  " ++ readerKey.asString ++ " <- [ "
				++ writersByReader[readerKey].asArray.sort.collect { |wk| wk.asString }.join(", ")
				++ " ]";
				wLine.postln;
			};
		};

		// Edges (writer -> readers)
		"edges (writer -> readers):".postln;
		if(readersByWriter.isNil || readersByWriter.isEmpty) {
			"  (none)".postln;
		} {
			readersByWriter.keys.asArray.sort.do { |writerKey|
				rLine = "  " ++ writerKey.asString ++ " -> [ "
				++ readersByWriter[writerKey].asArray.sort.collect { |rk| rk.asString }.join(", ")
				++ " ]";
				rLine.postln;
			};
		};


		"==========================".postln;
		^this;
	}

	dumpKey { |keySymbol|
		var stateLocal;

		"===== NdMNameSpace status =====".postln;

		if(stateByKey.isNil) {
			"stateByKey is nil".postln;
			"==========================".postln;
			^this;
		};

		stateLocal = stateByKey[keySymbol];

		if(stateLocal.isNil) {
			("no state for key: " ++ keySymbol).postln;
			"==========================".postln;
			^this;
		};

		this.dumpKeyBody(keySymbol, stateLocal, false);

		"==========================".postln;
		^this;
	}
}
