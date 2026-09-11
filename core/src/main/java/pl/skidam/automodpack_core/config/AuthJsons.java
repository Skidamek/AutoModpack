package pl.skidam.automodpack_core.config;

import java.util.HashMap;
import java.util.Map;

import pl.skidam.automodpack_core.auth.IssuedSecret;

public class AuthJsons {

	public static class SecretsFields {
		public Map<String, IssuedSecret> secrets = new HashMap<>();
	}
}
