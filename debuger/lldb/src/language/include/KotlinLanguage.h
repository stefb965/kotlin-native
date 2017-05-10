#ifndef liblldb_KotlinLanguage_h_
#define liblldb_KotlinLanguage_h_

#include <lldb/Target/Language.h>
#include <lldb/Utility/ConstString.h>
#include <lldb/lldb-private.h>

namespace lldb_private { 

class KotlinLanguage: public Language {
public:
  KotlinLanguage() = default;
  ~KotlinLanguage() override = default;
  lldb::LanguageType GetLanguageType() const override {
    return static_cast<lldb::LanguageType>(0x26);
  }
  
  static void Initialize();

  static void Terminate();

  static lldb_private::Language *CreateInstance(lldb::LanguageType language);

  static lldb_private::ConstString GetPluginNameStatic();

  ConstString GetPluginName() override;
  uint32_t GetPluginVersion() override;
};

}
#endif
