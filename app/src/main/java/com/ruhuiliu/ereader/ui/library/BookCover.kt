package com.ruhuiliu.ereader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ruhuiliu.ereader.data.db.BookEntity
import java.io.File

private val coverPalette = listOf(
    Color(0xFF3B5B7E) to Color(0xFF6C8EB4),
    Color(0xFF8A5A2B) to Color(0xFFC08A55),
    Color(0xFF2E6B4F) to Color(0xFF5FA07F),
    Color(0xFF6B3A5E) to Color(0xFFA06A8F),
    Color(0xFF7E3B3B) to Color(0xFFB46C6C),
    Color(0xFF3B4A7E) to Color(0xFF6C7BB4),
)

@Composable
fun BookCover(book: BookEntity, coverFile: File?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp))) {
        if (coverFile != null) {
            AsyncImage(
                model = coverFile,
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val (a, b) = coverPalette[(book.title.hashCode() and 0x7fffffff) % coverPalette.size]
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(a, b)))
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    book.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Box(Modifier.weight(1f))
                Text(
                    book.author.ifBlank { book.format },
                    color = Color.White.copy(alpha = .8f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
