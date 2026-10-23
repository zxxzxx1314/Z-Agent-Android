package cn.rain.zagent

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.io.File

object OcrHelper {
fun recognizeText(imagePath: String): String {
val file = File(imagePath)
if (!file.exists()) return "Error: File not found"
return try {
val bitmap = BitmapFactory.decodeFile(imagePath) ?: return "Error: Cannot decode image"
val image = InputImage.fromBitmap(bitmap, 0)
val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
val result = Tasks.await(recognizer.process(image))
result.text
} catch (e: Exception) {
"Error: ${e.message}"
}
}
}
