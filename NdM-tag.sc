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

        // NdMNameSpace 経由で、tag を持つ NdM インスタンス群を取得
        nodes = this.nodesForTag(tagSymbol);

        // それぞれに対して .play を呼ぶ（フェード時間は NdM 側の現在の設定を使用）
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
	// 実体の保持・逆引きは NdMNameSpace に委譲する。

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
