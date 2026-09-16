"""Derive host trigger switches from the optional application storage setting."""

STORAGE_KEY = "CONVERSION_STORAGE_CONNECTION_STRING"
QUEUE_STORAGE_KEY = "CONVERSION_QUEUE_CONNECTION_STRING"
TRIGGER_FUNCTIONS = ("ProcessConversion", "PoisonConversion")


def derive_settings(connection_string: str) -> dict[str, str]:
    connection_string = connection_string.strip()
    # Disabled triggers are still indexed before Java starts. A syntactically valid
    # emulator connection lets binding construction succeed without starting a listener.
    settings = {
        STORAGE_KEY: connection_string,
        QUEUE_STORAGE_KEY: connection_string or "UseDevelopmentStorage=true",
    }
    for function_name in TRIGGER_FUNCTIONS:
        settings[f"AzureWebJobs.{function_name}.Disabled"] = (
            "false" if connection_string else "true"
        )
    return settings
