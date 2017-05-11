lldb_private::LanguageRuntime
CreateInstance(Process *process, lldb::LanguageType language) {
  if (language == eLanguageTypeKotlin)
    return new KotlinLanguageRuntime(process);
  return nullptr;
}

static ConstString g_name("konan");

void KotlinLanguageRuntime::Initialize() {
  PluginManager::RegisterPlugin(g_name, "Kotlin Language Runtime", CreateInstance);
}

void KotlinLanguageRuntime::Terminate() {
  PluginManager::UnregisterPlugin(CreateInstance);
}

lldb_private::ConstString KotlinLanguageRuntime::GetPluginName() override {
  return g_name;
}

uint32_t KotlinLanguageRuntime::GetPluginVersion() { return 1;}

