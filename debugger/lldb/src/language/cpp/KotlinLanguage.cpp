#include <KotlinLanguage.h>
#include <lldb/Core/PluginManager.h>
#include <lldb/Utility/ConstString.h>
#include <lldb/API/SBDebugger.h>

using namespace lldb;
namespace lldb_private {

void KotlinLanguage::Initialize() {
  PluginManager::RegisterPlugin(GetPluginNameStatic(), "Kotlin Language",
                                CreateInstance);
}

void KotlinLanguage::Terminate() {
  PluginManager::UnregisterPlugin(CreateInstance);
}

lldb_private::ConstString KotlinLanguage::GetPluginNameStatic() {
  static ConstString g_name("Kotlin");
  return g_name;
}

lldb_private::ConstString KotlinLanguage::GetPluginName() {
  return GetPluginNameStatic();
}


uint32_t KotlinLanguage::GetPluginVersion() { return 1; }

Language *KotlinLanguage::CreateInstance(lldb::LanguageType language) {
  if (language == eLanguageTypeKotlin)
    return new KotlinLanguage();
  return nullptr;
}
}

namespace lldb {
  bool PluginInitialize(SBDebugger) {
    return true;
  }
}
/*--------------------------------------------------------------------------------
to compile test-bad 
# clang++ $(llvm-config-mp-devel --cflags --cxxflags --ldflags --system-libs --libs support) -llldb main.cpp -o plugin-prober

main.cpp:
#include <llvm/Support/DynamicLibrary.h>
#include <stdio.h>

int main() {
  std::string msg;
  llvm::sys::DynamicLibrary library = llvm::sys::DynamicLibrary::getPermanentLibrary("liblldbPluginKotlinLanguage.dylib", &msg);
  if (library.isValid()) {
    printf("plugin loaded");
  }
  else {
    printf("plugin not loaded: %s\n", msg.c_str());
  }
  return 0;
}
--------------------------------------------------------------------------------
plugin compile:
# clang++ -I/opt/local/libexec/llvm-devel/include -Idebuger/lldb/src/language/include/ debuger/lldb/src/language/cpp/KotlinLanguage.cpp -shared -fpic -o liblldbPluginKotlinLanguage.dylib --std=c++11 -Wl,-undefined,dynamic_lookup -Wl,-flat_namespace  $(llvm-config-mp-devel --ldflags) -llldbTarget

# ./a.out shows problems with binary.*/
