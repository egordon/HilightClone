#!/usr/bin/env python3
import pyglet
from pyglet.window import key
import queue
import threading
import sys
import time

# Construct Window
window = pyglet.window.Window(width=1400, height=788)
image = pyglet.resource.image('curiosity.jpg')

# Global frame counter
frame = int(0)
active_bit = None

# Send Vars
toggle = None
q = queue.Queue()
dalph = 0.1
ber_count = 0

@window.event
def on_key_press(symbol, modifiers):
    global toggle, dalph
    if symbol == key._0:
        if toggle == 0:
            print("Toggle 0 Off")
            toggle = None
        else:
            toggle = 0
            print("Toggle 0")
    elif symbol == key._1:
        if toggle == 1:
            print("Toggle 1 Off")
            toggle = None
        else:
            toggle = 1
            print("Toggle 1")
    elif symbol == key.A:
        if dalph == 0.1:
            print("delta alpha to 0.5")
            dalph = 0.5
        else:
            print("delta alpha to 0.1")
            dalph = 0.1


def get_bit():
    global toggle, q, ber_count
    # Continuous Bits
    if toggle == 0:
        return 0
    elif toggle == 1:
        return 1

    if ber_count > 0:
        ber_count = ber_count - 1
        return ber_count % 2

    # Sending Words
    if q.empty():
        bit = None
    else:
        bit = q.get_nowait();
        q.task_done();

    return bit

# Function to get quad
def get_quad(alpha):
    return pyglet.graphics.vertex_list(4,
        ('v2i', (0, 0,  1400, 0, 1400, 788, 0, 788)),
        ('c4B', (0, 0, 0, alpha, 
            0, 0, 0, alpha, 
            0, 0, 0, alpha, 
            0, 0, 0, alpha)))
# Drawing Loop
@window.event
def on_draw():
    global frame, active_bit, t, dalph
    window.clear()

    # Get Bit / Alpha
    alph = 0.0
    if frame % 12 == 0:
        active_bit = get_bit()

    if active_bit == 0: # 15Hz
        if frame % 4 == 1:
            alph = dalph
    elif active_bit == 1: # 20Hz
        if frame % 3 == 2:
            alph = dalph

    # Init Transparency
    pyglet.gl.glEnable(pyglet.gl.GL_BLEND)
    pyglet.gl.glBlendFunc(pyglet.gl.GL_SRC_ALPHA, pyglet.gl.GL_ONE_MINUS_SRC_ALPHA)

    # Draw Image and Transparency
    image.blit(0, 0)
    quad = get_quad(int(alph * 255.0))
    quad.draw(pyglet.gl.GL_QUADS)

    frame = (frame + 1) % 60

def update(dt):
    # update objects
    # [...]
    pass

pyglet.clock.schedule_interval(update, 1.0/60.0)

def tobits(s):
    result = []
    for c in s:
        bits = bin(ord(c))[2:]
        bits = '00000000'[len(bits):] + bits
        result.extend([int(b) for b in bits])
    return result

def word_input():
    global q, toggle, ber_count
    while True:
        string = input("Word To Send: ")
        if string == "quit":
            print("Exiting")
            sys.exit(0)
        elif string == "0":
            on_key_press(key._0, None)
        elif string == "1":
            on_key_press(key._1, None)
        elif string == "ber":
            ber_count = 200

            print("Testing...")
            print()
        else:
            bit_arr = tobits(string)
            for b in bit_arr:
                assert (b == 0 or b == 1)
                q.put_nowait(b)

            # Optional: End with 0's
            #for i in range(8):
            #    q.put_nowait(0)

            print("Sending...")
            toggle = None

            # Optional: Timing
            '''
            t0 = time.time()
            q.join()
            print("Sent!\nTime Taken: " + str(time.time() - t0))
            print()
            '''


thread = threading.Thread(target=word_input)
thread.start()

pyglet.app.run()