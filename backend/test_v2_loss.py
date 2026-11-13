import unittest
import torch
from loss_v2 import loss_v2, positive_weights


class LossTests(unittest.TestCase):
    def test_missing_tactics_have_zero_gradient_even_with_teacher(self):
        z=torch.zeros(4,9,requires_grad=True)
        y=torch.zeros_like(z); mask=torch.ones_like(z); mask[:,1:]=0
        loss_v2(z,y,mask,torch.ones(9),teacher=torch.full_like(z,-10)).backward()
        self.assertEqual(z.grad[:,1:].abs().sum().item(),0)
        self.assertGreater(z.grad[:,0].abs().sum().item(),0)

    def test_rare_positive_gradient_and_unmasked_counts(self):
        y=torch.zeros(100,9); y[0,8]=1
        mask=torch.ones_like(y); mask[20:,1:]=0
        weights=positive_weights(y,mask)
        self.assertEqual(weights[8].item(),19.)
        z=torch.zeros_like(y,requires_grad=True)
        loss_v2(z,y,mask,weights,teacher=torch.full_like(y,-20)).backward()
        self.assertLess(z.grad[0,8].item(),0)
        self.assertEqual(z.grad[20:,1:].abs().sum().item(),0)
        self.assertTrue(torch.isfinite(z.grad).all())


if __name__=='__main__': unittest.main()
