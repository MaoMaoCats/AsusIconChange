package com.huep.asusiconpackapplyer

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView

class IconPackListAdapter(
    private val context: Activity,
    private val items: List<IconPackItem>,
    private var selectedPosition: Int = 0
) : ArrayAdapter<IconPackItem>(context, R.layout.iconpack_row_layout, items) {

    fun setSelectedPosition(position: Int) {
        selectedPosition = position
        notifyDataSetChanged()
    }

    fun getSelectedPosition(): Int = selectedPosition

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val rowView = convertView ?: LayoutInflater.from(context).inflate(
            R.layout.iconpack_row_layout,
            parent,
            false
        )

        val item = items[position]
        val titleText = rowView.findViewById<TextView>(R.id.title)
        val subtitleText = rowView.findViewById<TextView>(R.id.description)
        val imageView = rowView.findViewById<ImageView>(R.id.icon)
        val radioButton = rowView.findViewById<RadioButton>(R.id.radio_button)

        titleText.text = item.name
        subtitleText.text = when {
            item.isDefault -> context.getString(R.string.default_icons_desc)
            item.isPlaceholder -> context.getString(R.string.no_icon_packs_desc)
            else -> item.packageName
        }

        if (item.icon != null) {
            imageView.setImageDrawable(item.icon)
        } else {
            imageView.setImageResource(R.mipmap.ic_launcher)
        }

        if (item.isPlaceholder) {
            radioButton.visibility = View.GONE
            rowView.isEnabled = false
        } else {
            radioButton.visibility = View.VISIBLE
            rowView.isEnabled = true
            radioButton.isChecked = (position == selectedPosition)
        }

        return rowView
    }
}
