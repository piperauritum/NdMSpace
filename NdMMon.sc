/* ============================================================
OBSOLETE CLASS — DO NOT USE

NdMMon is the legacy monitor used before NdMNameSpace.
It is fully retired and remains only for compatibility
with historical code and for reference during transition.

All active monitoring, bus-history storage, and NdM tracking
have been migrated to NdMNameSpace.

New code must NOT call NdMMon directly.
Use NdMNameSpace.acquire / register / unregister exclusively.
============================================================ */


// === Singleton for NdMs' monitoring and bus index reuse ===
//
// Usage:
// NdMMon.dumpStatus;
//
// NdMMon は NdM インスタンスの集合を監視するためのシングルトン。
// NdM が生成されるたびに acquire / registerNode が呼ばれ、free 時に unregisterNode / release が呼ばれる。
// すべての NdM が解放されたタイミングで cleanup を一度だけ実行する。
// さらに、key / argName ごとの Bus index を記録し、NdM 再生成時に再利用する。

NdMMon : Object {

	classvar <instance;

	// 現在 NdMMon を利用している NdM インスタンス数のカウンタ
	var <count;
	// 監視対象の NdM インスタンスを保持する集合（IdentitySet：同一性ベース）
	var <>nodes;
	// key(Symbol) -> (argName(Symbol) -> busIndex(Int)) のマップ
	var <>argBusTable;

	*acquire {
		var mon;

		// 既にシングルトンがあればそれを返し、なければ新規作成して init する。
		mon = instance;
		if(mon.isNil) {
			mon = super.new.init;
			instance = mon;
		};

		// 利用カウンタをインクリメントして、少なくとも 1 つの NdM が使っていることを記録
		mon.increment;
		^mon;
	}

	*release {
		var mon;

		// シングルトンが存在しなければ何もせず終了。
		mon = instance;
		if(mon.isNil) {
			^nil;
		};

		// 利用カウンタをデクリメントし、0 以下になったら cleanup を実行
		mon.decrement;
		if(mon.count <= 0) {
			mon.cleanup;
			// instance は維持して bus 情報を残す（key ごとの Bus 永続化）
		};
		^nil;
	}

	*dumpStatus {
		var mon;

		mon = instance;
		if(mon.isNil) {
			"NdMMon: no instance".postln;
		} {
			mon.dumpStatus;
		};
		^this;
	}

	init {
		// 初期状態ではカウンタ 0、空のノード集合と bus テーブルを用意。
		count = 0;
		nodes = IdentitySet.new;
		argBusTable = IdentityDictionary.new;
		^this;
	}

	registerNode { |node|
		// 新しく生成された NdM を監視対象セットに追加。
		// IdentitySet を使うことで同一インスタンスが重複して追加されないようになっている。
		nodes.add(node);
		^this;
	}

	unregisterNode { |node|
		// 解放される NdM を監視対象から取り除く。
		// release と組み合わせることで、最後の NdM 解放時に cleanup が呼ばれる。
		nodes.remove(node);
		^this;
	}

	increment {
		// NdM の生成や acquire 呼び出しのたびに呼ばれる利用カウンタのインクリメント。
		count = count + 1;
		^this;
	}

	decrement {
		// NdM の free 時などに呼ばれるデクリメント。
		// 0 未満にはしない（多重 free に耐えるためのガード）。
		if(count > 0) {
			count = count - 1;
		};
		^this;
	}

	cleanup {
		// すべての NdM が解放され、カウンタが 0 以下になったときに一度だけ呼ばれるクリーンアップ処理。
		// nodes / argBusTable / count を初期状態へ戻し、完全なクリーン状態にする。

		if(nodes.notNil) {
			nodes.clear;
		};

		if(argBusTable.notNil) {
			// key / argName ごとの Bus index 記録をすべて破棄する
			argBusTable = IdentityDictionary.new;
		};

		count = 0;

		"NdMMon: cleanup (no NdM instances)".postln;
		^this;
	}

	// ------------------------------------------------
	// 統合ステータス表示（ノード一覧＋argBusTable＋出力バス＋arg rate）
	// ------------------------------------------------
	dumpStatus {
		var nodesArray;

		"===== NdMMon status =====".postln;

		// Nodes セクション（Array を返して argBusTable 側で再利用）
		nodesArray = this.dumpNodesSection;

		"".postln;

		// argBusTable セクション（nodesArray を使って rate を引く）
		this.dumpBusTableSection(nodesArray);

		"==========================".postln;

		^this;
	}

	dumpNodesSection {
		var nodesLocal;
		var nodesArray;

		var keyStr;
		var idVal;
		var outVal;
		var argNamesLocal;
		var argRatesLocal;
		var argParts;
		var argListStr;
		var rateSym;
		var rateLabel;

		"-- Nodes --".postln;

		nodesLocal = nodes;
		if(nodesLocal.isNil) {
			nodesArray = Array.new(0);
		} {
			if(nodesLocal.respondsTo(\asArray)) {
				nodesArray = nodesLocal.asArray;
			} {
				// 想定外の型なら空とみなす
				nodesArray = Array.new(0);
			};
		};

		nodesArray.do { |node, idx|
			if(node.isNil) {
				("[" ++ idx ++ "] invalid node (nil)").postln;
			} {
				if((node.respondsTo(\key).not) || (node.respondsTo(\identityHash).not)) {
					("[" ++ idx ++ "] invalid node (missing key/identityHash)").postln;
				} {
					outVal = nil;
					if(node.respondsTo(\outbus)) {
						outVal = node.outbus;
					};

					argNamesLocal = nil;
					if(node.respondsTo(\argNames)) {
						argNamesLocal = node.argNames;
					};
					if(argNamesLocal.isNil) {
						argNamesLocal = Array.new(0);
					};

					argRatesLocal = nil;
					if(node.respondsTo(\argRates)) {
						argRatesLocal = node.argRates;
					};

					argParts = argNamesLocal.collect { |argName|
						rateSym = nil;
						rateLabel = "unknown";

						if(argRatesLocal.notNil) {
							rateSym = argRatesLocal[argName];
						};

						if(rateSym == \audio) {
							rateLabel = "audio";
						} {
							if(rateSym == \control) {
								rateLabel = "control";
							};
						};

						argName.asString ++ "(" ++ rateLabel ++ ")";
					};

					argListStr = argParts.join(", ");

					keyStr = node.key;
					idVal = node.identityHash;

					("[" ++ idx ++ "] key: " ++ keyStr ++ "  id: " ++ idVal).postln;
					("     outbus: " ++ outVal).postln;
					("     args: [" ++ argListStr ++ "]").postln;
				};
			};
		};

		// argBusTable セクションで再利用するために返す
		^nodesArray;
	}

	dumpBusTableSection { |nodesArray|
		var tableLocal;
		var table;

		var nodeForKey;
		var argDictLocal;
		var rateSym;
		var rateStr;

		"-- argBusTable --".postln;

		tableLocal = argBusTable;
		if(tableLocal.isNil) {
			table = IdentityDictionary.new;
		} {
			table = tableLocal;
		};

		// 想定外の型なら何も表示せず終了
		if(table.respondsTo(\keysValuesDo).not) {
			^this;
		};

		table.keysValuesDo { |ndmKey, argDict|
			if(argDict.isNil) {
				("key: " ++ ndmKey ++ " (invalid arg table)").postln;
				"".postln;
			} {
				if(argDict.respondsTo(\keysValuesDo).not) {
					("key: " ++ ndmKey ++ " (non-dictionary arg table)").postln;
					"".postln;
				} {
					argDictLocal = argDict;

					("key: " ++ ndmKey).postln;

					// 同じ key を持つ NdM を nodesArray から探す（無ければ nil）
					nodeForKey = nodesArray.detect { |node|
						var keyLocal;

						keyLocal = nil;
						if(node.notNil) {
							if(node.respondsTo(\key)) {
								keyLocal = node.key;
							};
						};
						keyLocal == ndmKey;
					};

					argDictLocal.keysValuesDo { |argName, busIndex|
						rateSym = nil;
						rateStr = "unknown";

						if(nodeForKey.notNil) {
							if(nodeForKey.respondsTo(\argRates)) {
								if(nodeForKey.argRates.notNil) {
									rateSym = nodeForKey.argRates[argName];
								};
							};
						};

						if(rateSym == \audio) {
							rateStr = "audio";
						} {
							if(rateSym == \control) {
								rateStr = "control";
							};
						};

						(
							"   " ++ argName.asString
							++ " -> bus " ++ busIndex
							++ " (" ++ rateStr ++ ")"
						).postln;
					};

					"".postln;
				};
			};
		};

		^this;
	}

	// ==========================
	// Bus index 管理用ヘルパ
	// ==========================

	storeBusIndex { |ndmKey, argName, busIndex|
		var tableForKey;
		var table;

		table = argBusTable;
		tableForKey = table[ndmKey];
		if(tableForKey.isNil) {
			tableForKey = IdentityDictionary.new;
			table[ndmKey] = tableForKey;
		};
		tableForKey[argName] = busIndex;

		^this;
	}

	lookupBusIndex { |ndmKey, argName|
		var tableForKey;
		var table;

		table = argBusTable;
		tableForKey = table[ndmKey];
		if(tableForKey.isNil) {
			^nil;
		};
		^tableForKey[argName];
	}

	clearArgName { |ndmKey, argName|
		var tableForKey;
		var table;

		table = argBusTable;
		tableForKey = table[ndmKey];
		if(tableForKey.notNil) {
			tableForKey.removeAt(argName);
		};
		^this;
	}
}
