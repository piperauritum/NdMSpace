+ NdM {

	// Update the function and output routing, reallocating/reusing buses as needed.
	setFunc { |funcIn, outbusIn|
		var serverLocal;
		var argList;
		var newArgNames;
		var newArgRates;
		var monitorLocal;
		var hasError;
		var switchLocal;
		var outTarget;
		var rawOut;
		// var outBusArg;
		// var ownerInfo;
		// var ownerNs;

		// SPEC:
		// - Update NdM's user function and output routing (outbus), then refresh arg tables and graph registration.
		// - outbusIn must be raw-only (outbus is nil / Integer / Array / Bus).
		// - On invalid input: reportOutBus + keep safe state (do not crash), fallback to 0.

		hasError = false;

		// Reject nil function input, since no graph can be built without it.
		if(funcIn.isNil) {
			this.debugPost("[NdM setFunc] funcIn is nil, key: " ++ key.asString);
			hasError = true;
		};

		if(hasError.not) {
			serverLocal = Server.default;
			func = funcIn;

			outTarget = outbusIn;
			rawOut = outTarget;

			if(rawOut.notNil) {

				if(
					(rawOut.notNil)
					&& (rawOut.isKindOf(Bus).not)
					&& (rawOut.isKindOf(Integer).not)
					&& (rawOut.isKindOf(Array).not)
				) {
					NdMError.reportOutBus(
						\outbusB,
						"NdM.setFunc@B-guardType",
						key,
						\B,
						"outbusIn",
						rawOut,
						"fallback0"
					);

					rawOut = 0;
				};
			};

			if(rawOut.isKindOf(NdMSpace)) {
				NdMError.reportOutBus(
					\outbusA,
					"NdM.setFunc@A-guardType",
					key,
					\A,
					"outbusIn",
					rawOut,
					"fallback0"
				);

				rawOut = 0;
			};

			this.setFuncPostStart(serverLocal, rawOut);

			// Update output bus if provided in the call.
			if(rawOut.notNil) {
				outbus = rawOut;
			};

			// Re-parse argument names and rates from the new user function.
			argList = this.parseArgList(func, keyRate);
			newArgNames = argList[0];
			newArgRates = argList[1];

			this.setFuncPostParsedArgs(newArgNames);

			this.setFuncEnsureTables;

			// Acquire the modern namespace for bus reuse/registration.
			monitorLocal = NdMNameSpace.acquire;
			this.debugPost("[NdM setFunc] NdMNameSpace.acquire -> " ++ monitorLocal.asString);

			// --- (1) Allocate or reuse buses for each new argument name (unified rule). ---
			this.setFuncUpdateArgBuses(newArgNames, newArgRates, serverLocal);

			// --- (2) Free buses for any argument names that no longer exist. ---
			this.setFuncFreeRemovedArgBuses(newArgNames);

			// Replace argument metadata with updated lists.
			this.setFuncCommitArgSpec(newArgNames, newArgRates);

			// --- (3) Request: update desired function and kick single-writer apply ---
			switchLocal = this.setFuncNormalizeSwitchDur(switchDur);
			this.setFuncRequestApplyKick(switchLocal);

			this.debugPost("[NdM setFunc] end");
		};

		^this;
	}

	setFuncPostStart { |serverLocal, outbusIn|
		var debugMsg;

		debugMsg = String.new;
		debugMsg = debugMsg
		++ "[NdM setFunc] start  key: " ++ key.asString
		++ "  server: " ++ serverLocal.asString;

		if(outbusIn.notNil) {
			debugMsg = debugMsg ++ "  outbusIn: " ++ outbusIn.asString;
		} {
			debugMsg = debugMsg ++ "  outbusIn: nil";
		};

		this.debugPost(debugMsg);

		^this;
	}

	setFuncPostParsedArgs { |newArgNames|
		this.debugPost("[NdM setFunc] parsed args  newArgNames: "
			++ newArgNames.asString
			++ "  oldArgNames: "
			++ (argNames ? Array.new).asString);

		^this;
	}

	setFuncEnsureTables {
		// Ensure argBuses mapping exists for update operations.
		if(argBuses.isNil) {
			argBuses = IdentityDictionary.new;
			this.debugPost("[NdM setFunc] argBuses was nil, created new IdentityDictionary");
		};

		// Ensure argRates mapping exists (required for removeAt).
		if(argRates.isNil) {
			argRates = IdentityDictionary.new;
		};

		^this;
	}

	setFuncUpdateArgBuses { |newArgNames, newArgRates, serverLocal|
		var argItem;
		var argNameLocal;
		var busLocal;
		var rateLocal;
		var res;

		newArgNames.do { |item|
			argItem = item;
			argNameLocal = argItem;

			busLocal = argBuses[argNameLocal];

			// If this NdM instance already has a bus, keep it.
			// Otherwise resolve via the unified resolver (record wins / inconsistent error / infer+remember both).
			if(busLocal.isNil) {
				res = NdM.resolveArgBus(key, argNameLocal, serverLocal);
				busLocal = res[0];
				rateLocal = res[1];

				argBuses[argNameLocal] = busLocal;
				argRates[argNameLocal] = rateLocal;

				this.debugPost("[NdM setFunc] arg resolved: " ++ argNameLocal.asString
					++ "  index: " ++ busLocal.index.asString
					++ "  rate: " ++ rateLocal.asString);
			} {
				// Keep existing mapping; ensure rate table is populated.
				rateLocal = argRates[argNameLocal] ? (newArgRates[argNameLocal] ? \audio);
				argRates[argNameLocal] = rateLocal;

				this.debugPost("[NdM setFunc] arg reuse existing bus: " ++ argNameLocal.asString
					++ "  index: " ++ busLocal.index.asString
					++ "  rate: " ++ rateLocal.asString);
			};
		};

		^this;
	}

	setFuncFreeRemovedArgBuses { |newArgNames|
		var oldItem;
		var oldNameLocal;
		var oldBusLocal;
		var oldRateLocal;
		var monitorLocal;

		monitorLocal = NdMNameSpace.acquire;

		if(argNames.notNil) {
			argNames.do { |item|
				oldItem = item;
				oldNameLocal = oldItem;

				if(newArgNames.includesEqual(oldNameLocal).not) {
					oldBusLocal = argBuses[oldNameLocal];
					if(oldBusLocal.notNil) {
						oldRateLocal = argRates[oldNameLocal] ? \audio;

						this.debugPost("[NdM setFunc] free old Bus  arg: "
							++ oldNameLocal.asString
							++ "  index: " ++ oldBusLocal.index.asString
							++ "  rate: " ++ oldRateLocal.asString);

						oldBusLocal.free;
						argBuses.removeAt(oldNameLocal);
						argRates.removeAt(oldNameLocal);
					} {
						this.debugPost("[NdM setFunc] old arg had no Bus (already freed): "
							++ oldNameLocal.asString);
					};

					if(monitorLocal.notNil) {
						monitorLocal.removeArg(key, oldNameLocal);
					};
				};
			};
		};

		^this;
	}

	setFuncCommitArgSpec { |newArgNames, newArgRates|
		argNames = newArgNames;
		argRates = newArgRates;

		this.debugPost("[NdM setFunc] argNames/argRates updated  argNames: "
			++ argNames.asString);

		^this;
	}

	setFuncNormalizeSwitchDur { |switchDurIn|
		var switchLocal;

		switchLocal = switchDurIn;
		if(switchLocal.isNil) {
			switchLocal = 0.0;
		};
		if(switchLocal < 0.0) {
			switchLocal = 0.0;
		};

		^switchLocal;
	}

	setFuncRequestApplyKick { |switchLocal|
		// Cancel pending free if user redefines/plays again.
		wantFree = false;

		wantFunc = func;
		wantSwitch = switchLocal;

		reqGen = reqGen + 1;
		this.debugPost("[NdM setFunc] requested apply  gen=" ++ reqGen.asString);

		this.applyKick;

		^this;
	}
}