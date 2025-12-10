+ NdM {

	// Update the function and output routing, reallocating/reusing buses as needed.
	setFunc { |funcIn, outbusIn|
		var server;
		var argList;
		var newArgNames;
		var newArgRates;
		var proxyFunc;
		var monitor1;
		var debugMsg;

		// Reject nil function input, since no graph can be built without it.
		if(funcIn.isNil) {
			this.debugPost("[NdM setFunc] funcIn is nil, key: " ++ key.asString);
			^this;
		};

		server = Server.default;
		func = funcIn;

		// Prepare a detailed debug log of the call context.
		debugMsg = String.new;
		debugMsg = debugMsg
		++ "[NdM setFunc] start  key: " ++ key.asString
		++ "  server: " ++ server.asString;
		if(outbusIn.notNil) {
			debugMsg = debugMsg
			++ "  outbusIn: " ++ outbusIn.asString;
		} {
			debugMsg = debugMsg ++ "  outbusIn: nil";
		};
		// Emit startup log for function switching.
		this.debugPost(debugMsg);

		// Update output bus if provided in the call.
		if(outbusIn.notNil) {
			outbus = outbusIn;
		};

		// Re-parse argument names and rates from the new user function.
		argList = this.parseArgList(func, keyRate);
		newArgNames = argList[0];
		newArgRates = argList[1];

		this.debugPost("[NdM setFunc] parsed args  newArgNames: "
			++ newArgNames.asString
			++ "  oldArgNames: "
			++ (argNames ? Array.new).asString);

		// Ensure argBuses mapping exists for update operations.
		if(argBuses.isNil) {
			argBuses = IdentityDictionary.new;
			this.debugPost("[NdM setFunc] argBuses was nil, created new IdentityDictionary");
		};

		// Acquire the modern namespace for bus reuse/registration.
		monitor1 = NdMNameSpace.acquire;
		this.debugPost("[NdM setFunc] NdMNameSpace.acquire -> " ++ monitor1.asString);

		// --- (1) Allocate or reuse buses for each new argument name. ---
		newArgNames.do { |argName|
			var bus;
			var busIndex;
			var rate;

			rate = newArgRates[argName] ? \audio;
			bus = argBuses[argName];

			if(bus.isNil) {
				// Attempt to recall bus index from namespace to reuse previous wiring.
				busIndex = nil;
				if(monitor1.notNil) {
					busIndex = monitor1.recallBus(key, argName);
				};

				this.debugPost("[NdM setFunc] arg: " ++ argName.asString
					++ "  rate: " ++ rate.asString
					++ "  recalledIndex: " ++ busIndex.asString);

				// Reuse recalled bus index or allocate new bus.
				if(busIndex.notNil) {
					bus = Bus.new(rate, busIndex, 1, server);
					this.debugPost("[NdM setFunc] reuse Bus index: " ++ busIndex.asString
						++ "  class: " ++ bus.class.asString);
				} {
					if(rate == \audio) {
						bus = Bus.audio(server, 1);
					} {
						bus = Bus.control(server, 1);
					};
					this.debugPost("[NdM setFunc] new Bus allocated  index: "
						++ bus.index.asString
						++ "  rate: " ++ rate.asString);
				};

				argBuses[argName] = bus;
			} {
				// Existing bus reused for arguments unchanged between functions.
				this.debugPost("[NdM setFunc] reuse existing Bus for arg: "
					++ argName.asString
					++ "  index: " ++ bus.index.asString
					++ "  rate: " ++ rate.asString);
			};

			// Commit latest bus/rate mapping to namespace.
			if(monitor1.notNil) {
				monitor1.rememberBus(key, argName, argBuses[argName].index);
				monitor1.rememberRate(key, argName, rate);
			};
		};

		// --- (2) Free buses for any argument names that no longer exist. ---
		if(argNames.notNil) {
			argNames.do { |oldName|
				var bus;
				var rate;

				if(newArgNames.includesEqual(oldName).not) {
					bus = argBuses[oldName];
					if(bus.notNil) {
						rate = argRates[oldName] ? \audio;
						this.debugPost("[NdM setFunc] free old Bus  arg: "
							++ oldName.asString
							++ "  index: " ++ bus.index.asString
							++ "  rate: " ++ rate.asString);
						bus.free;
						argBuses.removeAt(oldName);
						argRates.removeAt(oldName);
					} {
						this.debugPost("[NdM setFunc] old arg had no Bus (already freed): "
							++ oldName.asString);
					};
				};
			};
		};

		// Replace argument metadata with updated lists.
		argNames = newArgNames;
		argRates = newArgRates;

		this.debugPost("[NdM setFunc] argNames/argRates updated  argNames: "
			++ argNames.asString);

		// --- (3) If a proxy exists, update its source with crossfade logic. ---
		if(proxy.notNil) {
			var proxyLocal;
			var switchDurLocal;
			var srv;
			var threadLocal;

			proxyLocal = proxy;

			this.debugPost("[NdM setFunc] proxy present  isPlaying: "
				++ proxyLocal.isPlaying.asString
				++ "  sourceNil: " ++ proxyLocal.source.isNil.asString);

			if(proxyLocal.source.notNil) {
				// Determine crossfade duration used by gate envelope.
				switchDurLocal = switchDur ? 1.0;
				if(switchDurLocal < 0.0) {
					switchDurLocal = 0.0;
				};

				this.debugPost("[NdM setFunc] switchDurLocal: "
					++ switchDurLocal.asString);

				// No gate fade or proxy not playing → immediate source swap.
				if((switchDurLocal <= 0.0) || (proxyLocal.isPlaying.not)) {
					this.debugPost("[NdM setFunc] immediate source replace (no gate crossfade)");
					proxyFunc = this.makeProxyFunc;
					proxyLocal.source = proxyFunc;
					// New function starts with gate=1.
					proxyLocal.set(\ndmGate, 1);
				} {
					// Gate envelope crossfade: fade out old function, then replace.
					this.debugPost("[NdM setFunc] gateEnv crossfade start");

					srv = this.getServer;
					threadLocal = thisThread;

					if((srv.notNil) && { threadLocal.isKindOf(Routine) }) {
						try {
							srv.sync;
						} {
							this.debugPost("[NdM setFunc] Server:sync failed (ignored)");
						};
					} {
						this.debugPost("[NdM setFunc] skip Server:sync (not in Routine or no server)");
					};

					// Begin fade-out of the old source by lowering ndmGate.
					this.debugPost("[NdM setFunc] ndmGate = 0 (fade out old func)");
					proxyLocal.set(\ndmGate, 0);

					// After switchDurLocal seconds, place the new function.
					SystemClock.sched(switchDurLocal, {
						var proxyNow;
						var newProxyFunc;

						proxyNow = proxy;

						this.debugPost("[NdM setFunc] SystemClock.sched fired  key: "
							++ key.asString
							++ "  proxyNow: " ++ proxyNow.asString);

						if(proxyNow.notNil) {
							newProxyFunc = this.makeProxyFunc;
							proxyNow.source = newProxyFunc;
							this.debugPost("[NdM setFunc] source replaced inside sched, ndmGate=1");
							proxyNow.set(\ndmGate, 1);
						} {
							this.debugPost("[NdM setFunc] proxy disappeared before sched");
						};

						nil;
					});
				};
			} {
				// No existing source → simply attach new makeProxyFunc.
				this.debugPost("[NdM setFunc] proxy has no source yet, just assign makeProxyFunc");
				proxyFunc = this.makeProxyFunc;
				proxyLocal.source = proxyFunc;
			};
		} {
			// Proxy not created yet → only internal metadata updated.
			this.debugPost("[NdM setFunc] proxy is nil (only func/bus updated, no source)");
		};

		this.debugPost("[NdM setFunc] end");
		^this;
	}
}