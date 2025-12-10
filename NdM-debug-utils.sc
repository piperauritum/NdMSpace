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