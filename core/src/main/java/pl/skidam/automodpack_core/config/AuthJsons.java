package pl.skidam.automodpack_core.config;

import java.util.HashMap;
import java.util.Map;

import pl.skidam.automodpack_core.auth.Secrets;

public class AuthJsons {

	public static class SecretsFields {
		public Map<String, Secrets.Secret> secrets = new HashMap<>();
	}
}
