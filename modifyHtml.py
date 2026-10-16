import re

with open('app/src/main/java/com/example/ServerManager.kt', 'r') as f:
    content = f.read()

pattern = re.compile(r'val filesHtml = if \(files\.isEmpty\(\)\) \{.*?\n\s*\}\n\s*\}', re.DOTALL)

replacement = """val filesHtml = if (files.isEmpty()) {
                                "<p>No files on device.</p>"
                            } else {
                                files.joinToString("") { file ->
                                    val ext = file.name.substringAfterLast('.', "").lowercase()
                                    val isMedia = ext in listOf("mp4", "mp3", "wav", "webm", "mkv", "avi")
                                    val mediaTag = if (isMedia) {
                                        val tag = if (ext in listOf("mp4", "webm", "mkv", "avi")) "video" else "audio"
                                        \"\"\"
                                        <div style="margin-top: 10px; display: flex; flex-direction: column; gap: 10px;">
                                            <button onclick="playOnDevice('${file.name}')" style="background-color: #03DAC6; color: black; padding: 12px; font-size: 1.1em;">▶️ Play on TV (Remote Control)</button>
                                            <div style="background-color: #2c2c2c; padding: 10px; border-radius: 8px;">
                                                <p style="margin-top: 0; margin-bottom: 8px; font-size: 0.9em; color: #aaa;">Stream to Phone:</p>
                                                <$tag controls style="width: 100%;"><source src="/files/${file.name}"></$tag>
                                            </div>
                                        </div>
                                        \"\"\"
                                    } else ""
                                    
                                    \"\"\"
                                    <div class="card" style="margin-bottom: 15px; text-align: left;">
                                        <strong>${file.name}</strong> (${formatFileSize(file.size)})
                                        <br><a href="/files/${file.name}" download style="color: #03DAC6; text-decoration: none; font-weight: bold; margin-top: 5px; display: inline-block;">Download File</a>
                                        $mediaTag
                                    </div>
                                    \"\"\"
                                }
                            }"""

new_content = pattern.sub(replacement, content)

with open('app/src/main/java/com/example/ServerManager.kt', 'w') as f:
    f.write(new_content)
