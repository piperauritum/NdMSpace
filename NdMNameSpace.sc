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
which wipes all history and nodes.

DESIGN NOTES:
- NdMNameSpace performs Bus cleanup only on `.reset`.
NdM.free / freeAll do not free Bus objects individually.
- History persists across NdM re-creations.
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

	// *acquire {
	// 	var monitor;
	//
	// 	monitor = monInst;
	// 	if(monitor.isNil) {
	// 		monitor = super.new.init;
	// 		monInst = monitor;
	// 	};
	// 	^monitor;
	// }

	// instance
	// ---------------------------------------------------------------------------
	// Core API: return the current NdMNameSpace instance if it exists.
	// - Returns the existing singleton created by acquire.
	// - Returns nil if acquire has never been called.
	// - Used in places where the monitor is optional (e.g. free).

	*instance {
		^monInst;
	}

	// *reset {
	// 	// explicit reset: fully discard the previous singleton instance
	// 	"NdMNameSpace: reset".postln;
	// 	monInst = nil;            // drop old instance reference (hard reset)
	// 	monInst = super.new.init; // create a fresh new monitor instance
	// 	^monInst;
	// }

	*reset {
		var oldInst;
		var server;
		var stateTable;
		var argsTableLocal;
		var busIndexLocal;
		var rateSymbolLocal;
		var busLocal;

		"NdMNameSpace: reset".postln;

		oldInst = monInst;

		if(oldInst.notNil) {
			server = Server.default;
			stateTable = oldInst.stateByKey;

			if(stateTable.notNil) {
				stateTable.keysValuesDo { |keySymbol, stateLocal|
					var argsTableLocal2;

					// stateLocal は \node/\args/\tags を持つ IdentityDictionary のはず
					if(stateLocal.notNil) {
						argsTableLocal2 = stateLocal[\args];

						if(argsTableLocal2.notNil) {
							argsTableLocal2.keysValuesDo { |argNameLocal, argInfoLocal|
								busIndexLocal = argInfoLocal[\busIndex];

								if(busIndexLocal.notNil) {
									rateSymbolLocal = argInfoLocal[\rate];
									if(rateSymbolLocal.isNil) {
										rateSymbolLocal = \audio;
									};

									// NdM が予約していた bus をここで解放
									busLocal = Bus.new(rateSymbolLocal, busIndexLocal, 1, server);
									busLocal.free;
								};
							};
						};
					};
				};
			};
		};

		// ここでモニタを作り直す
		monInst = nil;
		monInst = super.new.init;
		^monInst;
	}

	*release {
		// フェーズ2の設計で参照カウントや cleanup 方針を決める。
		// ここでは何もしない最小スケルトン。
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

		^this;
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
