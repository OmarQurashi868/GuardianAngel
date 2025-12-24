package com.example.guardianangel.model

sealed class Screen {
    object Main : Screen()
    object Guardian : Screen()
    object Ward : Screen()
    data class GuardianConnected(val wardName: String) : Screen()
}
