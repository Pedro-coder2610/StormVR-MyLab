cmake_minimum_required(VERSION 3.16)
project(stormvr CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pasta do SDK OpenVR 2.15.6 (contem headers/, lib/win64/, bin/win64/)
set(OPENVR_SDK "Z:/Ferramentas/openvr-2.15.6" CACHE PATH "Pasta do SDK OpenVR")

set(PKG_DIR "${CMAKE_SOURCE_DIR}/package/stormvr")
set(BIN_DIR "${PKG_DIR}/bin/win64")

if(MSVC)
  add_compile_options(/utf-8 /W3)
endif()

# ---------- Driver (so precisa dos headers)
add_library(driver_stormvr SHARED driver/driver.cpp)
target_include_directories(driver_stormvr PRIVATE "${OPENVR_SDK}/headers" common)
target_link_libraries(driver_stormvr PRIVATE winmm ws2_32 user32)
set_target_properties(driver_stormvr PROPERTIES
  PREFIX ""
  RUNTIME_OUTPUT_DIRECTORY "${BIN_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")

# ---------- App de configuracao (aba na dashboard)
add_executable(stormvr_config WIN32 config/config_app.cpp)
target_include_directories(stormvr_config PRIVATE "${OPENVR_SDK}/headers" common)
target_link_libraries(stormvr_config PRIVATE "${OPENVR_SDK}/lib/win64/openvr_api.lib" user32 gdi32)
set_target_properties(stormvr_config PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${BIN_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${BIN_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${BIN_DIR}")
add_custom_command(TARGET stormvr_config POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different "${OPENVR_SDK}/bin/win64/openvr_api.dll" "${BIN_DIR}/openvr_api.dll"
  COMMAND ${CMAKE_COMMAND} -E copy_if_different "${CMAKE_SOURCE_DIR}/config/icon.png" "${BIN_DIR}/icon.png")

# ---------- StormVR Gamepad (controle do StormVR como Xbox 360 virtual para jogos de tela plana; precisa de ViGEmBus + ViGEmClient.dll)
add_executable(stormvr_gamepad gamepad/gamepad.cpp)
target_include_directories(stormvr_gamepad PRIVATE common)
target_link_libraries(stormvr_gamepad PRIVATE ws2_32)
set_target_properties(stormvr_gamepad PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/package/StormVR_Gamepad"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_SOURCE_DIR}/package/StormVR_Gamepad"
  RUNTIME_OUTPUT_DIRECTORY_DEBUG "${CMAKE_SOURCE_DIR}/package/StormVR_Gamepad")
