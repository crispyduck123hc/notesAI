package com.example.notesai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.notesai.db.DatabaseDriverFactory
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val driverFactory = DatabaseDriverFactory(context = this)
            App(driverFactory = driverFactory)
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    //App()
}