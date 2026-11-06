"""ComfyUI route extension; deliberately exports zero workflow nodes."""
import os

NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}


def install():
    import folder_paths
    from server import PromptServer
    from .service import MediaService

    instance = PromptServer.instance
    class QueueProxy:
        def __getattr__(self, name):
            queue = getattr(instance, 'prompt_queue', None)
            if queue is None:
                raise AttributeError(name)
            return getattr(queue, name)

    service = MediaService(
        {'input': folder_paths.get_input_directory(), 'output': folder_paths.get_output_directory()},
        QueueProxy(),
        secret=os.environ.get('COMFYCHAIR_DELETE_SECRET', ''),
        proxy_secret=os.environ.get('COMFYCHAIR_PROXY_SECRET', ''),
        trusted_proxy=os.environ.get('COMFYCHAIR_TRUSTED_PROXY_IP', ''),
        tombstone_file=os.environ.get('COMFYCHAIR_TOMBSTONE_FILE',
                                   os.path.join(os.path.dirname(__file__), 'missing.json')),
        auth_mode=os.environ.get('COMFYCHAIR_AUTH_MODE', 'proxy'))
    service.register(instance.routes)


# Unit tests import the pure service independently; normal ComfyUI import registers routes.
try:
    import folder_paths
except ImportError:
    pass
else:
    install()
