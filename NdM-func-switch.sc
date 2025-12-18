+ NdM {

	// Update the function and output routing, reallocating/reusing buses as needed.
	setFunc { |funcIn, outbusIn|
		var serverLocal;
		var argList;
		var newArgNames;
		var newArgRates;
		var monitorLocal;
		var debugMsg;
		var hasError;

		// hoisted temps (no mid-block var)
		var argItem;
		var argNameLocal;
		var busLocal;
		var busIndexLocal;
		var rateLocal;

		var oldItem;
		var oldNameLocal;
		var oldBusLocal;
		var oldRateLocal;

		var switchLocal;

		hasError = false;

		// Reject nil function input, since no graph can be built without it.
		if(funcIn.isNil) {
			this.debugPost("[NdM setFunc] funcIn is nil, key: " ++ key.asString);
			hasError = true;
		};

		if(hasError.not) {
			serverLocal = Server.default;
			func = funcIn;

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

			// Ensure argRates mapping exists (required for removeAt).
			if(argRates.isNil) {
				argRates = IdentityDictionary.new;
			};

			// Acquire the modern namespace for bus reuse/registration.
			monitorLocal = NdMNameSpace.acquire;
			this.debugPost("[NdM setFunc] NdMNameSpace.acquire -> " ++ monitorLocal.asString);

			// --- (1) Allocate or reuse buses for each new argument name. ---
			newArgNames.do { |item|
				argItem = item;
				argNameLocal = argItem;

				rateLocal = newArgRates[argNameLocal] ? \audio;
				busLocal = argBuses[argNameLocal];

				if(busLocal.isNil) {
					busIndexLocal = nil;
					if(monitorLocal.notNil) {
						busIndexLocal = monitorLocal.recallBus(key, argNameLocal);
					};

					this.debugPost("[NdM setFunc] arg: " ++ argNameLocal.asString
						++ "  rate: " ++ rateLocal.asString
						++ "  recalledIndex: " ++ busIndexLocal.asString);

					if(busIndexLocal.notNil) {
						busLocal = Bus.new(rateLocal, busIndexLocal, 1, serverLocal);
						this.debugPost("[NdM setFunc] reuse Bus index: " ++ busIndexLocal.asString
							++ "  class: " ++ busLocal.class.asString);
					} {
						if(rateLocal == \audio) {
							busLocal = Bus.audio(serverLocal, 1);
						} {
							busLocal = Bus.control(serverLocal, 1);
						};
						this.debugPost("[NdM setFunc] new Bus allocated  index: "
							++ busLocal.index.asString
							++ "  rate: " ++ rateLocal.asString);
					};

					argBuses[argNameLocal] = busLocal;
				} {
					this.debugPost("[NdM setFunc] reuse existing Bus for arg: "
						++ argNameLocal.asString
						++ "  index: " ++ busLocal.index.asString
						++ "  rate: " ++ rateLocal.asString);
				};

				if(monitorLocal.notNil) {
					monitorLocal.rememberBus(key, argNameLocal, argBuses[argNameLocal].index);
					monitorLocal.rememberRate(key, argNameLocal, rateLocal);
				};
			};

			// --- (2) Free buses for any argument names that no longer exist. ---
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
					};
				};
			};

			// Replace argument metadata with updated lists.
			argNames = newArgNames;
			argRates = newArgRates;

			this.debugPost("[NdM setFunc] argNames/argRates updated  argNames: "
				++ argNames.asString);

			// --- (3) Request: update desired function and kick single-writer apply ---
			switchLocal = switchDur;
			if(switchLocal.isNil) {
				switchLocal = 0.0;
			};
			if(switchLocal < 0.0) {
				switchLocal = 0.0;
			};

			// Cancel pending free if user redefines/plays again.
			wantFree = false;

			wantFunc = func;
			wantSwitch = switchLocal;

			reqGen = reqGen + 1;
			this.debugPost("[NdM setFunc] requested apply  gen=" ++ reqGen.asString);

			this.applyKick;

			this.debugPost("[NdM setFunc] end");
		};

		^this;
	}
}