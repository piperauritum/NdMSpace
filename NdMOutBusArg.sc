/*
NdMOutBusArg

Current status:
- NdMOutBusArg is NOT used in any active execution path of the NdM system.
- All downstream NdM code is strictly raw-only and must not accept NdMOutBusArg.
- Any accidental penetration of NdMOutBusArg into downstream code is treated as an error
  and is detected by regression tests (raw-only invariant).

Design intent:
- NdMOutBusArg is retained as a value object representing a *semantic outBus specification*
  that may exist at higher-level or external boundaries (e.g. future DSL/spec layers).
- It is intentionally isolated from runtime execution paths.
- This class currently serves as:
  1) a future extension hook for higher-level representations, and
  2) a sentinel type whose misuse is explicitly detected and rejected by tests.

Important:
- Do NOT unwrap, normalize, or consume NdMOutBusArg in downstream NdM code.
- If this type appears in runtime paths, it indicates a regression.

現状:
- NdMOutBusArg は、現在の NdM 実行パス上では使用されていない。
- 下流の NdM コードはすべて raw-only 前提であり、
  NdMOutBusArg を受け取ってはならない。
- NdMOutBusArg が誤って下流に侵入した場合はエラーとして検出され、
  回帰テストによって確実に捕捉される（raw-only 不変条件）。

設計意図:
- NdMOutBusArg は「意味的な outBus 指定」を保持するための値オブジェクトとして、
  将来の DSL / spec / 上流境界での利用可能性を残すために保持されている。
- 実行系からは意図的に隔離されている。
- 現在の役割は以下の2点に限定される：
  1) 将来拡張のための設計的フック
  2) 誤用を検出するための番兵（sentinel）型

注意:
- 下流の NdM コードで NdMOutBusArg を unwrap / 正規化 / 消費してはならない。
- 実行パスに現れた場合、それは回帰である。
*/

NdMOutBusArg : Object {

	var <>raw;
	var <>owner;

	*fromResolved { |rawIn, ownerInfo|
		var rawLocal;
		var ownerLocal;
		var ok;
		var elem;
		var msg;
		var outObj;

		// SPEC:
		// - Purpose: wrap a resolved out-bus target into NdMOutBusArg (B policy: NdM stores NdMOutBusArg).
		// - Accept rawIn (resolved form only): Integer / Bus / Array(Integer|Bus); other types are errors.
		// - On type error: NdMError(...).reportError (must log) then fallback raw=0 (do not stop execution).
		// - ownerInfo is recorded for later Bus(ownerInfo) resolution; may be nil.

		rawLocal = rawIn;
		ownerLocal = ownerInfo;

		ok = false;
		switch(rawLocal.class)
		{ Integer } {
			ok = true;
		}
		{ Bus } {
			ok = true;
		}
		{ Array } {
			ok = true;
			rawLocal.do { |v|
				elem = v;
				if((elem.isKindOf(Integer) || elem.isKindOf(Bus)).not) {
					ok = false;
				};
			};
		}
		{
			ok = false;
		};

		if(ok.not) {
			msg = "fallback0";
			if(ownerLocal.notNil) {
				msg = msg ++ " owner=" ++ ownerLocal.asString;
			};

			NdMError.reportOutBus(
				\outbusB,
				"NdMOutBusArg.fromResolved@B-guardType",
				nil,
				\B,
				"resolved",
				rawIn,
				msg
			);

			rawLocal = 0;
		};

		outObj = this.new;
		outObj.raw = rawLocal;
		outObj.owner = ownerLocal;
		^outObj;
	}

	bus {
		^raw;
	}

	asString {
		var msg;
		msg = "NdMOutBusArg(rawClass=" ++ raw.class.asString ++ ", raw=" ++ raw.asString ++ ")";
		^msg;
	}
}
