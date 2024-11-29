#version 300 es
/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
precision mediump float;

uniform sampler2D u_Texture;

in vec2 v_TexCoord;

uniform int action;

layout(location = 0) out vec4 o_FragColor;

void main() {
    // Mirror texture coordinates over the X axis
    vec2 texCoord = vec2(v_TexCoord.x, 1.0 - v_TexCoord.y);

    vec3 textureColor = texture(u_Texture, texCoord).rgb;

    if(action == 1){
            // Определение красного цвета с прозрачностью
            vec3 redColor = vec3(1.0, 0.0, 0.0);
            float alpha = 0.5; // Прозрачность

            // Смешивание текстуры с красным цветом
            vec3 mixedColor = mix(textureColor, redColor, alpha); // Меша с учетом прозрачности

            // Установка цвета выхода
            o_FragColor = vec4(mixedColor, 1.0);
    }
    else{
        // Установка цвета выхода
        o_FragColor = vec4(textureColor, 1.0);
    }
    return;
}