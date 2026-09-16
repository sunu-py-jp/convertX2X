"""Derive identity/connection-string bindings before Functions indexes triggers."""

STORAGE_KEY = "CONVERSION_STORAGE_CONNECTION_STRING"
QUEUE_STORAGE_KEY = "CONVERSION_QUEUE_CONNECTION_STRING"
TRIGGER_FUNCTIONS = ("ProcessConversion", "PoisonConversion")
MI_KEYS = tuple("CONVERSION_STORAGE__" + suffix for suffix in ("blobServiceUri", "queueServiceUri", "clientId"))
QUEUE_MI_KEYS = tuple(QUEUE_STORAGE_KEY + "__" + suffix for suffix in ("queueServiceUri", "credential", "clientId"))


def derive_settings(source: str | dict[str, str]) -> dict[str, str]:
    values = {STORAGE_KEY: source} if isinstance(source, str) else source
    connection = values.get(STORAGE_KEY, "").strip()
    blob, queue, client = (values.get(key, "").strip() for key in MI_KEYS)
    if connection and (blob or queue or client):
        raise ValueError("Choose one Storage authentication mode")
    if not connection and bool(blob) != bool(queue):
        raise ValueError("Both Blob and Queue service URIs are required")
    enabled = bool(connection or (blob and queue))
    settings = {STORAGE_KEY: connection, QUEUE_STORAGE_KEY: connection or ("" if enabled else "UseDevelopmentStorage=true")}
    settings.update(dict.fromkeys(MI_KEYS + QUEUE_MI_KEYS, ""))
    if not connection and enabled:
        settings.update(dict(zip(MI_KEYS, (blob, queue, client))))
        settings.update(dict(zip(QUEUE_MI_KEYS, (queue, "managedidentity", client))))
    for name in TRIGGER_FUNCTIONS:
        settings[f"AzureWebJobs.{name}.Disabled"] = str(not enabled).lower()
    maintenance = any(key.startswith("CONVERSION_RESULT_QUEUE_") and value.strip() for key, value in values.items())
    for key in ("CONVERSION_RESULT_RETENTION_DAYS", "CONVERSION_STATE_RETENTION_DAYS"):
        value = values.get(key, "0").strip() or "0"
        if not value.isdecimal():
            raise ValueError("Retention settings must be nonnegative integers")
        maintenance = maintenance or int(value) > 0
    settings["AzureWebJobs.MaintainConversions.Disabled"] = str(not (enabled and maintenance)).lower()
    for key in obsolete_keys(settings):
        settings.pop(key, None)
    return settings


def obsolete_keys(settings: dict[str, str]) -> list[str]:
    identity = bool(settings.get("CONVERSION_STORAGE__blobServiceUri")) and not settings.get(STORAGE_KEY)
    if identity:
        keys = [STORAGE_KEY, QUEUE_STORAGE_KEY]
        if not settings.get("CONVERSION_STORAGE__clientId"):
            keys += ["CONVERSION_STORAGE__clientId", QUEUE_STORAGE_KEY + "__clientId"]
        return keys
    return list(MI_KEYS + QUEUE_MI_KEYS)
