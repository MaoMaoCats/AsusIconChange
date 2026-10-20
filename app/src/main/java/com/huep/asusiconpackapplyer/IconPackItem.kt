package com.huep.asusiconpackapplyer

import android.graphics.drawable.Drawable

data class IconPackItem(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val isDefault: Boolean = false,
    val isPlaceholder: Boolean = false
)
