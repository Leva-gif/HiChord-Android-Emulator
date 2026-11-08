cmake_minimum_required(VERSION 3.22.1)
project("hichord_pocket_dsp")

find_package(oboe REQUIRED CONFIG)

add_library(
    hichord_dsp
    SHARED
    native-lib.cpp
)

target_link_libraries(
    hichord_dsp
    oboe::oboe
    android
    log
)
