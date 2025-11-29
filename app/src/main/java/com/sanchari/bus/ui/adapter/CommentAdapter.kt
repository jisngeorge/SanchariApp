package com.sanchari.bus.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.data.model.UserComment
import com.sanchari.bus.databinding.ItemCommentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommentAdapter(
    private var comments: List<UserComment>
) : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    private val outputFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        holder.bind(comments[position])
    }

    override fun getItemCount(): Int = comments.size

    fun updateComments(newComments: List<UserComment>) {
        comments = newComments
        notifyDataSetChanged()
    }

    inner class CommentViewHolder(private val binding: ItemCommentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(comment: UserComment) {
            binding.textViewUsername.text = comment.username.ifEmpty { "Anonymous" }

            try {
                // Multiply Unix time (seconds) by 1000L to get milliseconds
                val date = Date(comment.commentDate * 1000L)
                binding.textViewCommentDate.text = outputFormat.format(date)
            } catch (_: Exception) {
                // Fallback if timestamp is invalid
                binding.textViewCommentDate.text = "---"
            }
            binding.textViewCommentText.text = comment.commentText
        }
    }
}