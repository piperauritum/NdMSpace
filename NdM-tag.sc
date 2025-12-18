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
