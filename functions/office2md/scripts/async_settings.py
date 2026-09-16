"""Derive optional async listeners and maintenance from connection or MI settings."""

STORAGE_KEY = "CONVERSION_STORAGE_CONNECTION_STRING"
QUEUE_STORAGE_KEY = "CONVERSION_QUEUE_CONNECTION_STRING"
TRIGGER_FUNCTIONS = ("ProcessConversion", "PoisonConversion")
MI_KEYS = ("blobServiceUri", "queueServiceUri", "clientId")


def derive_settings(connection_string: str, values: dict[str, str] | None = None) -> dict[str, str]:
    values = values or {}
    connection_string = connection_string.strip()
    mi = {key: values.get(f"CONVERSION_STORAGE__{key}", "").strip() for key in MI_KEYS}
    if connection_string and any(mi.values()):
        raise ValueError("Choose a connection string or managed identity settings, not both.")
    if not connection_string and bool(mi["blobServiceUri"]) != bool(mi["queueServiceUri"]):
        raise ValueError("Managed identity requires both blobServiceUri and queueServiceUri.")
    enabled = bool(connection_string or (mi["blobServiceUri"] and mi["queueServiceUri"]))
    settings = {STORAGE_KEY: connection_string}
    settings.update({f"CONVERSION_STORAGE__{key}": value for key, value in mi.items()})
    if connection_string or not enabled:
        # Blank the inactive identity settings when switching authentication modes.
        settings[QUEUE_STORAGE_KEY] = connection_string or "UseDevelopmentStorage=true"
        settings.update({f"{QUEUE_STORAGE_KEY}__{key}": "" for key in ("queueServiceUri", "credential", "clientId")})
    else:
        # Azure must remove this exact setting: even an empty exact key can shadow its MI prefix.
        settings[QUEUE_STORAGE_KEY] = ""
        settings[f"{QUEUE_STORAGE_KEY}__queueServiceUri"] = mi["queueServiceUri"]
        settings[f"{QUEUE_STORAGE_KEY}__credential"] = "managedidentity"
        settings[f"{QUEUE_STORAGE_KEY}__clientId"] = mi["clientId"]
    for function_name in TRIGGER_FUNCTIONS:
        settings[f"AzureWebJobs.{function_name}.Disabled"] = "false" if enabled else "true"
    maintenance = enabled and (any(k.startswith("CONVERSION_RESULT_QUEUE_") and k.endswith("__queueName") and v.strip() for k, v in values.items())
                               or any(int(values.get(k, "0") or "0") > 0 for k in ("CONVERSION_RESULT_RETENTION_DAYS", "CONVERSION_STATE_RETENTION_DAYS")))
    settings["AzureWebJobs.MaintainConversions.Disabled"] = "false" if maintenance else "true"
    return settings


def obsolete_keys(settings: dict[str, str]) -> list[str]:
    """Settings that must be absent, not empty, for Azure connection-prefix resolution."""
    keys = []
    if settings.get(f"{QUEUE_STORAGE_KEY}__credential") == "managedidentity":
        keys.append(QUEUE_STORAGE_KEY)
    else:
        keys.extend(f"{QUEUE_STORAGE_KEY}__{key}" for key in ("queueServiceUri", "credential", "clientId"))
    if not settings.get(f"{QUEUE_STORAGE_KEY}__clientId"):
        keys.append(f"{QUEUE_STORAGE_KEY}__clientId")
    return list(dict.fromkeys(keys))
