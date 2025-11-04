package com.sanchari.bus

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sanchari.bus.databinding.ItemCommentBinding

class CommentAdapter(
    private var comments: List<UserComment>
) : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

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
            binding.textViewCommentDate.text = comment.commentDate // You could format this date later
            binding.textViewCommentText.text = comment.commentText
        }
    }
}
