/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef lldb_KotlinLanguageRuntime_h_
#define lldb_KotlinLanguageRuntime_h_
#include "lldb/Breakpoint/BreakpointResolver.h"
#include "lldb/Symbol/Type.h"
#include "lldb/Core/Value.h"
#include "lldb/Target/LanguageRuntime.h"
#include "lldb/lldb-private.h"
#include <common.h>
namespace lldb_private {
class KotlinLanguageRuntime:public LanguageRuntime {
public:
  ~KotlinLanguageRuntime() override = default;
  static lldb_private::LanguageRuntime*
  CreateInstance(Process *process, lldb::LanguageType language);

  lldb::LanguageType GetLanguageType() const override {
    return eLanguageTypeKotlin;
  }

  static void Initialize();
  static void Terminate();

  bool GetObjectDescription(Stream&, ValueObject&) override { return false; }
  bool GetObjectDescription(Stream&, Value&, ExecutionContextScope*) override { return false;}
  bool GetDynamicTypeAndAddress(ValueObject&, lldb::DynamicValueType, TypeAndOrName&, Address&, Value::ValueType&) override { return false; }

  bool CouldHaveDynamicValue(ValueObject&) override { return false;}
  TypeAndOrName FixUpDynamicType(const TypeAndOrName&, ValueObject&) override { return (TypeAndOrName());}
  lldb::BreakpointResolverSP CreateExceptionResolver(Breakpoint *, bool, bool) override {
    return lldb::BreakpointResolverSP();
  }
  lldb_private::ConstString GetPluginName() override;
  
  uint32_t GetPluginVersion() override;

private:
  KotlinLanguageRuntime(Process *process):LanguageRuntime(process){}
};
}
#endif
